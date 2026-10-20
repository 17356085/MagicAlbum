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
