"""Offline checks of M4 acceptance safety and completeness; no Docker starts."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location("community_runtime_gate", Path(__file__).with_name("community-runtime-smoke.py"))
gate = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = gate
SPEC.loader.exec_module(gate)


class CommunityRuntimeGuards(unittest.TestCase):
    def setUp(self):
        parent = gate.ROOT_DIR / "outputs" / "m4-gate-unit"
        parent.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=parent)
        self.root = Path(self.temp.name).resolve()

    def tearDown(self):
        self.temp.cleanup()

    def test_admin_bootstrap_preserves_credentials_origin_and_object_url(self):
        runtime = gate.ComposeRuntime(self.root)
        runtime.update_public_base_url(12345)
        runtime.update_browser_origin(23456)
        runtime.set_admin_email("admin@example.test")
        runtime.update_public_base_url(12345)
        self.assertTrue(runtime.project.startswith("bluealbum-m4-"))
        self.assertEqual(runtime.env_values["APP_ADMIN_EMAIL"], "admin@example.test")
        self.assertEqual(runtime.env_values["APP_CORS_ALLOWED_ORIGINS"], "http://127.0.0.1:23456")
        self.assertEqual(runtime.env_values["S3_PUBLIC_BASE_URL"], "http://127.0.0.1:12345/media-test")
        with self.assertRaises(gate.GateFailure):
            runtime.set_admin_email("admin@example.test\nOTHER=value")
        runtime.cleanup()
        self.assertFalse(runtime.env_file.exists())

    def test_existing_project_is_not_claimed_and_no_down_is_attempted(self):
        runtime = gate.ComposeRuntime(self.root)
        def docker(*args, **kwargs):
            self.assertIn("-a", args)
            return "unrelated-existing-container"
        runtime.docker = docker
        with self.assertRaises(gate.GateFailure):
            runtime.bootstrap()
        self.assertFalse(runtime.compose_touched)
        runtime.cleanup()
        self.assertFalse(runtime.env_file.exists())

    def test_report_requires_exact_names_no_duplicates_no_skips_and_cleanup(self):
        reporter = gate.M4Reporter()
        self.assertFalse(gate.validate_report(reporter, "passed"))
        reporter.cases = [gate.CaseResult(name, "passed", 0) for name in gate.PLANNED_CASES]
        self.assertTrue(gate.validate_report(reporter, "passed"))
        self.assertFalse(gate.validate_report(reporter, "failed"))
        reporter.cases[-1] = reporter.cases[0]
        self.assertFalse(gate.validate_report(reporter, "passed"))
        reporter.cases = [gate.CaseResult(name, "skipped" if i == 0 else "passed", 0) for i, name in enumerate(gate.PLANNED_CASES)]
        self.assertFalse(gate.validate_report(reporter, "passed"))

    def test_failed_bootstrap_writes_failed_evidence_and_deletes_private_env(self):
        base = gate.ComposeRuntime
        class FakeRuntime(base):
            def bootstrap(self):
                raise gate.GateFailure("controlled preflight failure")
        with patch.object(gate, "ComposeRuntime", FakeRuntime):
            with self.assertRaises(SystemExit) as error:
                gate.main(["--artifact-dir", str(self.root)])
            self.assertEqual(error.exception.code, 1)
        report_file = next(self.root.glob("*/junit.xml"))
        report = ET.parse(report_file).getroot()
        self.assertEqual(report.attrib["tests"], "1")
        self.assertEqual(report.attrib["failures"], "1")
        self.assertEqual(list(self.root.glob("*/runtime.env")), [])
        self.assertEqual(json.loads(report_file.with_name("checks.json").read_text())["status"], "failed")

    def test_backend_recreation_cannot_silently_change_frontend_or_object_ports(self):
        runtime = gate.ComposeRuntime(self.root)
        runtime.ports = {"backend": 12000, "frontend": 23000, "s3mock": 34000}
        runtime.compose = lambda *args, **kwargs: ""
        def refresh():
            runtime.ports["frontend"] += 1
        runtime.refresh_ports = refresh
        with self.assertRaises(gate.GateFailure):
            runtime.restart_backend_with_admin_email()
        runtime.cleanup()

    def test_runtime_construction_failure_cannot_exit_success(self):
        with patch.object(gate, "ComposeRuntime", side_effect=OSError("controlled creation failure")):
            with self.assertRaises(SystemExit) as error:
                gate.main(["--artifact-dir", str(self.root)])
            self.assertEqual(error.exception.code, 1)


if __name__ == "__main__":
    unittest.main()
