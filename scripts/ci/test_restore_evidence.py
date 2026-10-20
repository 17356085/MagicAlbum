"""Exercise restore-evidence rejection without running containers."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("restore_check", Path(__file__).with_name("check-restore-evidence.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class RestoreEvidenceTest(unittest.TestCase):
    def setUp(self):
        temporary_root = Path(__file__).resolve().parents[2] / "outputs" / "restore-evidence-unit"
        temporary_root.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=temporary_root)
        self.root = Path(self.temp.name)
        self.checks = {"Succeeded": True, "CleanupErrors": [], "Checks": [{"Name": str(i), "Passed": True} for i in range(14)]}
        for index, name in enumerate(gate.STAGE_CHECKS):
            self.checks["Checks"][index]["Name"] = name
        self.stage = {k: True for k in ("passed", "permissionsVerified", "summaryVerified", "notificationVerified", "notificationIsolationVerified", "auditVerified", "idempotencyVerified", "likeVerified")}
        self.stage["provider"] = "mock"
        self.backup = {
            "Status": "PASSED", "RequireStageFixtures": True, "UploadComparison": {"Passed": True},
            "DatabaseComparisons": [{"Passed": True, "StableTablesCompared": 4} for _ in range(4)],
            "RequiredBusinessTables": [{"Table": t, "SourceCount": 1, "RestoreCount": 1, "Match": True} for t in sorted(gate.STAGE_TABLES)],
            "SourceUploadFileCount": 1, "SourceUploadBytes": 68,
            "SourceUploadManifestSha256": "a" * 64, "RestoreUploadManifestSha256": "a" * 64,
        }
        self.report = self.root / "backup" / "fresh" / "verification-summary.json"
        self.report.parent.mkdir(parents=True)
        for name, count in (("api-junit.xml", 14), ("selenium-junit.xml", 1), ("browser-results.xml", 1)):
            (self.root / name).write_text("<testsuite>" + "<testcase/>" * count + "</testsuite>")
        self.write()

    def tearDown(self):
        self.temp.cleanup()

    def write(self):
        (self.root / "checks.json").write_text(json.dumps(self.checks))
        self.report.write_text(json.dumps(self.backup))
        (self.root / "stage-restore-readback.json").write_text(json.dumps(self.stage))

    def test_complete_evidence(self):
        self.assertEqual(gate.validate(self.root)["stageTables"], 11)

    def test_reject_missing_empty_changed_table_or_upload(self):
        original = copy.deepcopy(self.backup)
        for mode in ("missing", "empty", "changed", "hash", "comparison"):
            with self.subTest(mode=mode):
                self.backup = copy.deepcopy(original)
                if mode == "missing": self.backup["RequiredBusinessTables"].pop()
                if mode == "empty": self.backup["RequiredBusinessTables"][0]["SourceCount"] = 0
                if mode == "changed": self.backup["RequiredBusinessTables"][0]["RestoreCount"] = 2
                if mode == "hash": self.backup["RestoreUploadManifestSha256"] = "b" * 64
                if mode == "comparison": self.backup["DatabaseComparisons"][0]["Passed"] = False
                self.write()
                with self.assertRaises(ValueError): gate.validate(self.root)

    def test_reject_cleanup_credentials_short_or_skipped_execution(self):
        self.checks["CleanupErrors"] = ["remaining volume"]
        self.write()
        with self.assertRaises(ValueError): gate.validate(self.root)

        self.checks["CleanupErrors"] = []
        self.write()
        (self.root / "source.env").write_text("test-only")
        with self.assertRaises(ValueError): gate.validate(self.root)
        (self.root / "source.env").unlink()
        (self.root / "api-junit.xml").write_text("<testsuite><testcase/></testsuite>")
        with self.assertRaises(ValueError): gate.validate(self.root)
        (self.root / "api-junit.xml").write_text("<testsuite>" + "<testcase><skipped/></testcase>" * 14 + "</testsuite>")
        with self.assertRaises(ValueError): gate.validate(self.root)

    def test_reject_absent_semantic_readback_or_stage_execution(self):
        self.stage["permissionsVerified"] = False
        self.write()
        with self.assertRaisesRegex(ValueError, "semantics"): gate.validate(self.root)
        self.stage["permissionsVerified"] = True
        self.checks["Checks"] = []
        self.write()
        with self.assertRaisesRegex(ValueError, "did not execute"): gate.validate(self.root)


if __name__ == "__main__":
    unittest.main()
