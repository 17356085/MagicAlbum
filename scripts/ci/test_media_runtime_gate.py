"""Offline guard tests for the destructive/acceptance boundaries; no Docker is started."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location("media_runtime_gate", Path(__file__).with_name("media-runtime-smoke.py"))
gate = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = gate
SPEC.loader.exec_module(gate)


class RuntimeGuardTests(unittest.TestCase):
    def test_auth_recreation_refreshes_dynamic_ports_before_health_and_recovery(self):
        acceptance = gate.Acceptance.__new__(gate.Acceptance)
        runtime = MagicMock()
        acceptance.runtime = runtime
        runtime.artifact_dir = self.root
        runtime.ports = {"media-worker": 10001}
        current_port = [10001]
        def compose(*args, **kwargs):
            if args[0] == "up": current_port[0] += 1
        runtime.compose.side_effect = compose
        runtime.refresh_ports.side_effect = lambda: runtime.ports.update({"media-worker": current_port[0]})
        runtime.wait_http.side_effect = lambda url, *a, **k: self.assertIn(f":{current_port[0]}/", url)
        acceptance.refresh_urls = MagicMock()
        acceptance.owner = MagicMock()
        acceptance.fixture = b"original"
        acceptance.fixture_sha = "original-hash"
        acceptance.upload = MagicMock(return_value={"assetId": "asset"})
        pending = {"job_id": "job", "status": "PENDING", "attempts": 0, "output_key": None}
        complete = {**pending, "status": "SUCCEEDED", "attempts": 1, "output_sha256": "preview-hash"}
        acceptance.sql_job = MagicMock(side_effect=[pending, pending, pending, complete])
        acceptance.worker_auth_pause_events = MagicMock(return_value=1)
        acceptance.rabbit_request = MagicMock(return_value=MagicMock(status_code=200, json=lambda: {"consumers": 0, "messages_ready": 1}))
        acceptance.poll_succeeded = MagicMock(return_value={"url": "original", "previewUrl": "preview"})
        acceptance.fetch_object = MagicMock(side_effect=[b"original", b"preview"])
        with patch.object(gate.time, "sleep"):
            acceptance.worker_auth_pause_recovery()
        self.assertEqual(runtime.refresh_ports.call_count, 2)
        self.assertTrue((self.root / "worker-auth-pause.json").exists())

    def test_auth_pause_log_evidence_uses_only_the_fixed_parsed_event(self):
        acceptance = gate.Acceptance.__new__(gate.Acceptance)
        acceptance.runtime = MagicMock()
        acceptance.runtime.compose.return_value = '\n'.join([
            'worker | {"event":"job_handled","category":"core_auth_paused"}',
            'worker | {"event":"consumer_paused","category":"core_auth_paused"}',
            'worker | {"event":"consumer_paused","category":"other"}',
            'malformed {not-json}',
        ])
        self.assertEqual(acceptance.worker_auth_pause_events(), 1)
        acceptance.runtime.compose.assert_called_once_with('logs', '--no-color', '--tail', '100', 'media-worker', timeout=30)

    def setUp(self):
        parent = gate.ROOT_DIR / "outputs" / "m3-gate-unit"
        parent.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=parent)
        self.root = Path(self.temporary.name).resolve()
        self.assertTrue(self.root.is_relative_to(gate.ROOT_DIR / "outputs"))

    def tearDown(self):
        self.temporary.cleanup()

    def test_compose_failure_detail_redacts_bare_runtime_secret(self):
        runtime = gate.ComposeRuntime(self.root)
        secret = runtime.secrets["RABBITMQ_PASSWORD"]
        result = MagicMock(returncode=1, stdout=b"", stderr=f"failed with {secret}".encode())
        with patch.object(gate.subprocess, "run", return_value=result):
            with self.assertRaises(gate.GateFailure) as raised:
                runtime.compose("port", "--index", "1", "rabbitmq", "5672")
        detail = str(raised.exception)
        self.assertNotIn(secret, detail)
        self.assertIn("[redacted]", detail)

    def test_restart_waits_for_new_port_without_using_old_mapping(self):
        runtime = gate.ComposeRuntime.__new__(gate.ComposeRuntime)
        runtime.ports = {"backend": 1111}
        runtime.compose = MagicMock()
        snapshots = iter([{}, {"backend": 2222}])
        def refresh():
            runtime.ports = next(snapshots)
            return runtime.ports
        runtime.refresh_ports = MagicMock(side_effect=refresh)

        with patch.object(gate.time, "monotonic", side_effect=[0, 1]), patch.object(gate.time, "sleep") as sleep:
            runtime.restart_service("backend")

        self.assertEqual(runtime.ports, {"backend": 2222})
        self.assertEqual(runtime.refresh_ports.call_count, 2)
        self.assertEqual(runtime.compose.call_args_list[0].args, ("stop", "backend"))
        self.assertEqual(runtime.compose.call_args_list[1].args, ("start", "backend"))
        self.assertEqual(runtime.compose.call_count, 2)
        sleep.assert_called_once()

    def test_restart_missing_port_fails_with_service_name(self):
        runtime = gate.ComposeRuntime.__new__(gate.ComposeRuntime)
        runtime.ports = {"backend": 1111}
        runtime.compose = MagicMock()
        def refresh():
            runtime.ports = {}
            return runtime.ports
        runtime.refresh_ports = MagicMock(side_effect=refresh)

        with patch.object(gate.time, "monotonic", side_effect=[0, 60]), patch.object(gate.time, "sleep"):
            with self.assertRaisesRegex(gate.GateFailure, "backend"):
                runtime.restart_service("backend")
        self.assertNotIn("backend", runtime.ports)

    def test_restart_does_not_hide_port_inventory_errors(self):
        runtime = gate.ComposeRuntime.__new__(gate.ComposeRuntime)
        runtime.compose = MagicMock()
        runtime.refresh_ports = MagicMock(side_effect=gate.GateFailure("inventory failed"))
        with self.assertRaisesRegex(gate.GateFailure, "inventory failed"):
            runtime.restart_service("backend")

    def test_refresh_ports_excludes_expected_stopped_broker_even_if_ps_race_says_running(self):
        runtime = gate.ComposeRuntime(self.root)
        runtime.ports = {"rabbitmq": 15672, "rabbitmq-management": 15673, "media-worker": 19091}
        calls = []

        def compose(*args, **kwargs):
            calls.append((args, kwargs))
            if args[0] == "ps":
                return json.dumps([
                    {"Service": "rabbitmq", "State": "running"},
                    {"Service": "media-worker", "State": "running"},
                ])
            self.assertEqual(args[0], "port")
            self.assertEqual(args[3], "media-worker")
            return "127.0.0.1:29091"

        runtime.compose = compose
        ports = runtime.refresh_ports(exclude_services={"rabbitmq", "rabbitmq-management"})

        self.assertEqual(ports, {"media-worker": 29091})
        self.assertEqual(runtime.ports, ports)
        self.assertEqual([call[0][3] for call in calls if call[0][0] == "port"], ["media-worker"])

    def test_broker_restart_waits_for_fresh_broker_ports_before_readiness(self):
        acceptance = gate.Acceptance.__new__(gate.Acceptance)
        runtime = MagicMock()
        acceptance.runtime = runtime
        acceptance.owner = MagicMock()
        acceptance.fixture = b"original"
        acceptance.fixture_sha = "original-hash"
        old_ports = {
            "rabbitmq": 15672,
            "rabbitmq-management": 15673,
            "backend": 18080,
            "notification-service": 18081,
            "gateway": 19000,
            "frontend": 19080,
            "media-worker": 19091,
            "s3mock": 19090,
        }
        stopped_ports = {
            "backend": 28080,
            "notification-service": 28081,
            "gateway": 29000,
            "frontend": 29080,
            "media-worker": 29091,
            "s3mock": 29090,
        }
        new_ports = {**stopped_ports, "rabbitmq": 29001, "rabbitmq-management": 29002}
        runtime.ports = old_ports.copy()
        runtime.compose.return_value = ""
        runtime.wait_http.return_value = None
        acceptance.upload = MagicMock()
        acceptance.fetch_object = MagicMock()
        acceptance.asset_status = MagicMock()
        acceptance.poll_succeeded = MagicMock()
        acceptance.upload.return_value = {"assetId": "asset", "url": "http://original"}
        acceptance.fetch_object.return_value = acceptance.fixture
        acceptance.asset_status.return_value = (MagicMock(status_code=200), {"processingStatus": "PENDING"})
        acceptance.poll_succeeded.return_value = {"processingStatus": "SUCCEEDED"}

        def refresh_ports(**kwargs):
            self.assertEqual(kwargs, {"exclude_services": {"rabbitmq", "rabbitmq-management"}})
            runtime.ports = stopped_ports.copy()
            return runtime.ports

        runtime.refresh_ports.side_effect = refresh_ports

        def wait_for_ports(services, **kwargs):
            self.assertEqual(set(services), {"rabbitmq", "rabbitmq-management"})
            self.assertGreater(kwargs.get("timeout", 30), 0)
            self.assertEqual(acceptance.management, "")
            self.assertEqual(acceptance.backend, "http://127.0.0.1:28080")
            runtime.ports = new_ports.copy()
            return runtime.ports

        runtime.wait_for_ports.side_effect = wait_for_ports

        with patch.object(gate.time, "sleep"):
            acceptance.broker_restart_recovery()

        runtime.refresh_ports.assert_called_once_with(
            exclude_services={"rabbitmq", "rabbitmq-management"}
        )
        runtime.wait_for_ports.assert_called_once()
        self.assertNotIn(15672, runtime.ports.values())
        self.assertNotIn(15673, runtime.ports.values())
        self.assertEqual(runtime.ports["rabbitmq"], 29001)
        self.assertEqual(runtime.ports["rabbitmq-management"], 29002)
        self.assertEqual(acceptance.gateway, "http://127.0.0.1:29000")
        self.assertEqual(acceptance.frontend, "http://127.0.0.1:29080")
        self.assertEqual(acceptance.backend, "http://127.0.0.1:28080")
        self.assertEqual(acceptance.notification, "http://127.0.0.1:28081")
        self.assertEqual(acceptance.s3, "http://127.0.0.1:29090")
        self.assertEqual(acceptance.management, "http://127.0.0.1:29002")

    def test_refresh_urls_clears_management_when_broker_is_allowed_missing(self):
        acceptance = gate.Acceptance.__new__(gate.Acceptance)
        runtime = MagicMock()
        acceptance.runtime = runtime
        runtime.ports = {
            "backend": 28080,
            "notification-service": 28081,
            "gateway": 29000,
            "frontend": 29080,
            "media-worker": 29091,
            "s3mock": 29090,
        }
        acceptance.management = "http://127.0.0.1:15673"

        acceptance.refresh_urls(allow_broker_missing=True)

        self.assertEqual(acceptance.backend, "http://127.0.0.1:28080")
        self.assertEqual(acceptance.notification, "http://127.0.0.1:28081")
        self.assertEqual(acceptance.gateway, "http://127.0.0.1:29000")
        self.assertEqual(acceptance.frontend, "http://127.0.0.1:29080")
        self.assertEqual(acceptance.s3, "http://127.0.0.1:29090")
        self.assertEqual(acceptance.management, "")
        with self.assertRaisesRegex(gate.GateFailure, "management URL is unavailable"):
            acceptance.rabbit_request("GET", "/api/overview")

        runtime.ports["rabbitmq-management"] = 29002
        acceptance.refresh_urls()
        self.assertEqual(acceptance.management, "http://127.0.0.1:29002")

        runtime.ports.pop("rabbitmq-management")
        with self.assertRaisesRegex(gate.GateFailure, "management port is missing"):
            acceptance.refresh_urls()

    def test_wait_for_ports_retries_only_transient_target_port_failure(self):
        runtime = gate.ComposeRuntime.__new__(gate.ComposeRuntime)
        runtime.ports = {"rabbitmq": 15672, "rabbitmq-management": 15673}
        port_calls = []

        def compose(*args, **kwargs):
            if args[0] == "ps":
                return json.dumps([{"Service": "rabbitmq", "State": "running"}])
            port_calls.append(args)
            if len(port_calls) == 1:
                raise gate.GateFailure(
                    'compose port --index failed with exit 1: service "rabbitmq" is not running container #1'
                )
            return "127.0.0.1:29001" if args[4] == "5672" else "127.0.0.1:29002"

        runtime.compose = compose
        with patch.object(gate.time, "sleep"):
            ports = runtime.wait_for_ports({"rabbitmq", "rabbitmq-management"}, timeout=5)

        self.assertEqual(ports, {"rabbitmq": 29001, "rabbitmq-management": 29002})
        self.assertEqual(len(port_calls), 3)

    def test_wait_for_ports_propagates_not_running_error_for_other_service(self):
        runtime = gate.ComposeRuntime.__new__(gate.ComposeRuntime)
        runtime.ports = {}
        port_calls = []

        def compose(*args, **kwargs):
            if args[0] == "ps":
                return json.dumps([{"Service": "rabbitmq", "State": "running"}])
            port_calls.append(args)
            raise gate.GateFailure(
                'compose port --index failed with exit 1: service "backend" is not running container #1'
            )

        runtime.compose = compose
        with self.assertRaisesRegex(gate.GateFailure, 'service "backend"'):
            runtime.wait_for_ports({"rabbitmq"}, timeout=5)
        self.assertEqual(len(port_calls), 1)

    def test_wait_for_ports_propagates_other_service_port_errors(self):
        runtime = gate.ComposeRuntime.__new__(gate.ComposeRuntime)
        runtime.ports = {}

        def compose(*args, **kwargs):
            if args[0] == "ps":
                return json.dumps([
                    {"Service": "rabbitmq", "State": "running"},
                    {"Service": "backend", "State": "running"},
                ])
            if args[3] == "backend":
                raise gate.GateFailure("permanent backend port inventory failure")
            return "127.0.0.1:29001" if args[4] == "5672" else "127.0.0.1:29002"

        runtime.compose = compose
        with self.assertRaisesRegex(gate.GateFailure, "permanent backend"):
            runtime.wait_for_ports({"rabbitmq", "rabbitmq-management"}, timeout=5)

    def test_wait_for_ports_has_explicit_timeout_for_target_port_race(self):
        runtime = gate.ComposeRuntime.__new__(gate.ComposeRuntime)
        runtime.ports = {"rabbitmq": 15672}

        def compose(*args, **kwargs):
            if args[0] == "ps":
                return json.dumps([{"Service": "rabbitmq", "State": "running"}])
            raise gate.GateFailure(
                'compose port --index failed with exit 1: service "rabbitmq" is not running container #1'
            )

        runtime.compose = compose
        with patch.object(gate.time, "monotonic", side_effect=[0, 0.25, 1]), patch.object(gate.time, "sleep"):
            with self.assertRaisesRegex(gate.GateFailure, "timed out waiting for mapped host port for rabbitmq"):
                runtime.wait_for_ports({"rabbitmq"}, timeout=1)

    def test_port_inventory_handles_s3_only_and_does_not_query_stopped_services(self):
        runtime = gate.ComposeRuntime(self.root)
        calls = []
        def compose(*args, **kwargs):
            calls.append(args)
            if args[0] == "ps": return json.dumps([{"Service": "s3mock", "State": "running"}, {"Service": "media-worker", "State": "exited"}])
            return "127.0.0.1:12345"
        runtime.compose = compose
        self.assertEqual(runtime.refresh_ports(), {"s3mock": 12345})
        self.assertEqual([call[3] for call in calls if call[0] == "port"], ["s3mock"])
        runtime.cleanup()
        self.assertFalse(runtime.env_file.exists())

    def test_preflight_checks_stopped_containers_and_never_claims_existing_project(self):
        runtime = gate.ComposeRuntime(self.root)
        def docker(*args, **kwargs):
            self.assertIn("-a", args)
            return "existing-stopped-container"
        runtime.docker = docker
        with self.assertRaises(gate.GateFailure): runtime.bootstrap()
        self.assertFalse(runtime.compose_touched)
        runtime.cleanup()

    def test_browser_origin_uses_exact_discovered_port_and_survives_env_refresh(self):
        runtime = gate.ComposeRuntime(self.root)
        runtime.update_public_base_url(12345)
        runtime.update_browser_origin(23456)
        runtime.update_public_base_url(12345)
        self.assertEqual(runtime.env_values["APP_CORS_ALLOWED_ORIGINS"], "http://127.0.0.1:23456")
        self.assertEqual(runtime.env_values["S3_PUBLIC_BASE_URL"], "http://127.0.0.1:12345/media-test")
        self.assertIn("APP_CORS_ALLOWED_ORIGINS=http://127.0.0.1:23456", runtime.env_file.read_text())
        self.assertIn("APP_CORS_ALLOWED_ORIGINS: ${APP_CORS_ALLOWED_ORIGINS}", runtime.override_file.read_text())
        with self.assertRaises(gate.GateFailure): runtime.update_browser_origin(0)
        runtime.cleanup()

    def test_cleanup_failure_cannot_exit_success_and_produces_failed_junit(self):
        real = gate.ComposeRuntime
        class FakeRuntime(real):
            def bootstrap(self): pass
            def build(self): pass
            def start(self): pass
            def cleanup(self):
                super().cleanup()
                self.cleanup_status = "failed"
        with patch.object(gate, "ComposeRuntime", FakeRuntime), patch.object(gate, "Acceptance", return_value=MagicMock()):
            with self.assertRaises(SystemExit) as error:
                gate.main(["--artifact-dir", str(self.root)])
            self.assertEqual(error.exception.code, 1)
        report = ET.parse(next(self.root.glob("*/junit.xml"))).getroot()
        self.assertEqual(report.attrib["failures"], "1")
        self.assertIsNotNone(report.find("testcase[@name='cleanup']/failure"))
        self.assertEqual(list(self.root.glob("*/runtime.env")), [])

    def test_bootstrap_failure_is_executed_failure_and_removes_credentials(self):
        real = gate.ComposeRuntime
        class FakeRuntime(real):
            def bootstrap(self): raise gate.GateFailure("controlled Docker preflight failure")
        with patch.object(gate, "ComposeRuntime", FakeRuntime):
            with self.assertRaises(SystemExit) as error:
                gate.main(["--artifact-dir", str(self.root)])
            self.assertEqual(error.exception.code, 1)
        report = ET.parse(next(self.root.glob("*/junit.xml"))).getroot()
        self.assertEqual(report.attrib["tests"], "1")
        self.assertEqual(report.attrib["failures"], "1")
        self.assertEqual(list(self.root.glob("*/runtime.env")), [])

    def test_sse_frames_distinguish_reset_from_actual_notification(self):
        frames = gate.Acceptance.stream_events(["event:reset", "id:1", 'data:{"kind":"reset"}', "", "event:notification", "id:2", 'data:{"cursor":"2","notificationId":"1","kind":"created"}', ""])
        self.assertEqual([frame["event"] for frame in frames], ["reset", "notification"])
        self.assertEqual(frames[1]["data"]["notificationId"], "1")


if __name__ == "__main__":
    unittest.main()
