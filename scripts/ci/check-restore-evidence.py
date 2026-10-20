"""Require current full-stack restore evidence; never accept a build as restore."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

STAGE_TABLES = {
    "ai_summary_task", "ai_summary_attempt", "ai_summary_budget", "ai_summary_request",
    "community_state", "community_admin_role", "community_moderator_scope",
    "community_posting_restriction", "community_audit",
    "notification_stream_cursor", "notification_stream_event",
}
STAGE_CHECKS = {
    "Stage permissions, summary ledger and notification fixtures persist through APIs",
    "Restored permissions, summary ledger and notification fixtures match API readback",
}


def validate(root: Path) -> dict:
    checks = json.loads((root / "checks.json").read_text(encoding="utf-8-sig"))
    if checks.get("Succeeded") is not True or checks.get("CleanupErrors") != []:
        raise ValueError("Runtime execution or owned-resource cleanup failed")
    actual = checks.get("Checks", [])
    if len(actual) < 14 or any(c.get("Passed") is not True for c in actual):
        raise ValueError("Full runtime and stage API readback checks did not execute")
    if not STAGE_CHECKS.issubset({c.get("Name") for c in actual}):
        raise ValueError("Stage fixture creation and restored API assertions are missing")
    stage = json.loads((root / "stage-restore-readback.json").read_text(encoding="utf-8-sig"))
    if stage.get("provider") != "mock" or any(stage.get(k) is not True for k in (
        "passed", "permissionsVerified", "summaryVerified", "notificationVerified", "notificationIsolationVerified", "auditVerified", "idempotencyVerified", "likeVerified",
    )):
        raise ValueError("Restored stage API semantics did not pass")
    for filename, count in (("api-junit.xml", 14), ("selenium-junit.xml", 1), ("browser-results.xml", 1)):
        report = ET.parse(root / filename).getroot()
        cases = list(report.iter("testcase"))
        if len(cases) != count or any(list(c) and any(n.tag in {"failure", "error", "skipped"} for n in c) for c in cases):
            raise ValueError(f"{filename} did not execute exactly {count} passing cases")
        if any(int(s.get(k, "0")) for s in report.iter("testsuite") for k in ("failures", "errors", "skipped")):
            raise ValueError(f"{filename} has unsuccessful suite results")
    reports = list((root / "backup").glob("*/verification-summary.json"))
    if len(reports) != 1:
        raise ValueError("Exactly one fresh independent restore report is required")
    backup = json.loads(reports[0].read_text(encoding="utf-8-sig"))
    if backup.get("Status") != "PASSED" or backup.get("UploadComparison", {}).get("Passed") is not True:
        raise ValueError("Database or upload restore did not pass")
    if backup.get("RequireStageFixtures") is not True:
        raise ValueError("Stage fixtures were not required by the backup verifier")
    comparisons = backup.get("DatabaseComparisons", [])
    if len(comparisons) != 4 or any(c.get("Passed") is not True or c.get("StableTablesCompared", 0) < 1 for c in comparisons):
        raise ValueError("Both databases must compare before and after application startup")
    tables = {row["Table"]: row for row in backup.get("RequiredBusinessTables", [])}
    if not STAGE_TABLES.issubset(tables):
        raise ValueError("Stage business tables were not required in restore evidence")
    for name in STAGE_TABLES:
        row = tables[name]
        if row.get("Match") is not True or row.get("SourceCount", 0) < 1 or row.get("RestoreCount") != row.get("SourceCount"):
            raise ValueError(f"Nonempty stage table {name} did not restore exactly")
    if backup.get("SourceUploadFileCount", 0) < 1 or backup.get("SourceUploadBytes", 0) < 1:
        raise ValueError("Restore fixture must contain an uploaded image")
    if backup.get("SourceUploadManifestSha256") != backup.get("RestoreUploadManifestSha256"):
        raise ValueError("Restored image inventory hash changed")
    if list(root.glob("*.env")):
        raise ValueError("Generated private runtime environments remain after cleanup")
    return {"runtimeChecks": len(actual), "browserAndApiTests": 16, "stageTables": len(STAGE_TABLES), "restore": "passed"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    print(json.dumps(validate(parser.parse_args().directory), ensure_ascii=False))
