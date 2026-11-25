#!/usr/bin/env python3
"""Measure the actual following-feed repository against disposable MySQL 8 data."""

import argparse
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import time


ROOT = Path(__file__).resolve().parents[2]
MYSQL_IMAGE = "mysql:8.0"
FIXTURE_LABEL = "com.bluealbum.feed-evaluation"


def execute(*args, environment=None, timeout=30):
    return subprocess.run(args, cwd=ROOT, env=environment, text=True, encoding="utf-8", errors="replace",
                          capture_output=True, timeout=timeout, check=False)


def docker(*args, environment=None, timeout=30):
    result = execute("docker", *args, environment=environment, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"Docker {args[0]} could not prepare or inspect the isolated MySQL fixture.")
    return result.stdout.strip()


def wait_for_database(container):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        result = execute(
            "docker", "exec", container, "sh", "-ec",
            'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --host=127.0.0.1 --user="$MYSQL_USER" '
            '"$MYSQL_DATABASE" --batch --skip-column-names -e "SELECT 1"',
            timeout=10,
        )
        if result.returncode == 0 and result.stdout.strip() == "1":
            return
        time.sleep(1)
    raise RuntimeError("The isolated MySQL fixture did not finish initialization.")


def cleanup(container, run_id):
    owned = docker("inspect", "--format", '{{ index .Config.Labels "' + FIXTURE_LABEL + '" }}', container)
    if owned != run_id:
        raise RuntimeError("The recorded container no longer belongs to this Feed experiment.")
    volumes = json.loads(docker("inspect", "--format", "{{json .Mounts}}", container))
    owned_volumes = {mount["Name"] for mount in volumes if mount.get("Type") == "volume"}
    docker("rm", "--force", "--volumes", container)
    remaining = set(docker("volume", "ls", "--quiet").splitlines())
    return not owned_volumes.intersection(remaining) and not docker(
        "ps", "-aq", "--filter", f"label={FIXTURE_LABEL}={run_id}",
    )


def revision():
    commit = execute("git", "rev-parse", "HEAD")
    cleanliness = execute("git", "status", "--porcelain")
    if commit.returncode or cleanliness.returncode:
        raise RuntimeError("The Feed experiment requires a Git checkout.")
    sha, clean = commit.stdout.strip(), not cleanliness.stdout.strip()
    configured = os.environ.get("GITHUB_SHA")
    if configured and (configured != sha or not clean):
        raise RuntimeError("CI requires a clean checkout of the configured commit.")
    return sha, clean


def verify_report(path, database):
    result = json.loads(path.read_text(encoding="utf-8"))
    mysql = result["mysql"]
    checks = result["mutationFlags"]
    keyset = result["matchedDeepKeyset"]
    contract = result["deployedCursorContract"]
    completed = (
        mysql["product"] == "MySQL" and mysql["database"] == database
        and result["fixture"]["threadCount"] >= 20_000
        and len(result["repositoryMeasurements"]) == 2 and len(result["plans"]) == 4
        and all(isinstance(plan.get("query_block"), dict) for plan in result["plans"].values())
        and keyset["sameOrderedIds"] is True and keyset["prototypePage"]["samples"] == 8
        and all(contract[key] is True for key in (
            "nextPageStable", "previousPageStable", "directJumpReanchors", "liveVisibility",
            "invalidAndOtherOwnerRejected",
        ))
        and all(checks[key] is True for key in (
            "staticTieOrderingStable", "offsetDuplicateObserved", "keysetAvoidedDuplicate",
            "keysetDropsHidden", "keysetDropsDeleted", "keysetDropsUnfollowed",
        ))
        and checks["apiChanged"] is False
        and result["credentialsIncluded"] is False
    )
    if not completed:
        raise RuntimeError("The Feed report is missing measured or mutation evidence.")


def run(args):
    maven = shutil.which(args.maven)
    if maven is None:
        raise RuntimeError("Maven must be available for the real MySQL Feed experiment.")
    artifact_root = args.artifact_dir.resolve()
    if artifact_root == ROOT / "outputs" or not artifact_root.is_relative_to(ROOT / "outputs"):
        raise RuntimeError("Feed experiment evidence must be stored under a workspace outputs subdirectory.")
    head, clean = revision()
    run_id = secrets.token_hex(6)
    out = artifact_root / f"feed-{run_id}"
    out.mkdir(parents=True, exist_ok=False)
    started = {"commit": head, "cleanWorktree": clean, "runId": run_id, "status": "failed", "cleanup": "not-run"}
    manifest = out / "source-revision.json"
    container = None
    database = f"ba_feed_runtime_{run_id}"
    password = secrets.token_hex(24)
    root_password = secrets.token_hex(24)
    credentials = {"MYSQL_DATABASE": database, "MYSQL_USER": "feed_fixture", "MYSQL_PASSWORD": password,
                   "MYSQL_ROOT_PASSWORD": root_password}
    options = [option for name in credentials for option in ("--env", name)]
    try:
        identifier = docker(
            "run", "-d", "--name", f"bluealbum-feed-{run_id}", "--label", f"{FIXTURE_LABEL}={run_id}",
            "--publish", "127.0.0.1::3306", "--memory", "768m", "--memory-swap", "768m",
            *options, MYSQL_IMAGE,
            environment={**os.environ, **credentials}, timeout=120,
        )
        if not re.fullmatch("[0-9a-f]{64}", identifier):
            raise RuntimeError("MySQL fixture did not return a complete container ID.")
        container = identifier
        started["mysqlImage"] = docker("inspect", "--format", "{{.Image}}", container)
        port = re.fullmatch(r"127\.0\.0\.1:(\d+)", docker("port", container, "3306/tcp"))
        if not port:
            raise RuntimeError("MySQL fixture must publish its port exclusively on loopback.")
        wait_for_database(container)
        environment = {
            **os.environ,
            "FEED_CHECK_MYSQL_URL": (f"jdbc:mysql://127.0.0.1:{port.group(1)}/{database}"
                                     "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                                     "&rewriteBatchedStatements=true"),
            "FEED_CHECK_DATABASE": database,
            "FEED_CHECK_USER": credentials["MYSQL_USER"],
            "FEED_CHECK_PASSWORD": password,
            "FEED_CHECK_OUTPUT": str(out / "feed-query-cost.json"),
        }
        result = execute(
            maven, "-B", "-pl", "backend", "-am", "-Dsurefire.failIfNoSpecifiedTests=false",
            "-Dtest=FollowingFeedCostAcceptance", "test", environment=environment, timeout=420,
        )
        log = (result.stdout + "\n" + result.stderr).replace(password, "[redacted]").replace(
            root_password, "[redacted]",
        )
        (out / "maven.log").write_text(log, encoding="utf-8")
        report = out / "feed-query-cost.json"
        if result.returncode or not report.is_file():
            raise RuntimeError(f"The real MySQL Feed experiment failed. See {out}.")
        verify_report(report, database)
        started["status"] = "passed"
    finally:
        try:
            if container is not None:
                started["cleanup"] = "failed"
                if cleanup(container, run_id):
                    started["cleanup"] = "passed"
        finally:
            if started["cleanup"] != "passed":
                started["status"] = "failed"
            manifest.write_text(json.dumps(started, indent=2) + "\n", encoding="utf-8")
    if started["cleanup"] != "passed":
        raise RuntimeError("The isolated Feed experiment resources did not clean up.")
    print(f"MySQL Feed experiment passed. Evidence: {out}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maven", default="mvn")
    parser.add_argument("--artifact-dir", type=Path, default=ROOT / "outputs" / "m9-feed-ci")
    args = parser.parse_args()
    try:
        run(args)
    except (RuntimeError, subprocess.TimeoutExpired) as error:
        # Timeout messages can contain command arguments. Print only the bounded reason.
        raise SystemExit(str(error) if isinstance(error, RuntimeError) else "The Feed experiment timed out.") from None


if __name__ == "__main__":
    main()
