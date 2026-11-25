"""Offline guards for the explicit full Python regression entry point."""

from __future__ import annotations

import importlib.util
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock
from urllib.error import HTTPError


ROOT = Path(__file__).resolve().parents[2]
SMOKE = ROOT / "scripts" / "ci" / "compose-browser-smoke.sh"
HELPER = ROOT / "scripts" / "ci" / "prepare-python-full-regression.py"

_HELPER_SPEC = importlib.util.spec_from_file_location("full_regression_helper", HELPER)
if _HELPER_SPEC is None or _HELPER_SPEC.loader is None:  # pragma: no cover - repository wiring failure
    raise RuntimeError("could not load the full-regression helper for offline tests")
FULL_HELPER = importlib.util.module_from_spec(_HELPER_SPEC)
_HELPER_SPEC.loader.exec_module(FULL_HELPER)

PRIVATE_VALUES = {
    "TEST_EMAIL": "full-regression@example.test",
    "TEST_PHONE": "13123456789",
    "TEST_PASSWORD": "offline-only-secret",
    "FULL_TEST_USER_ID": "42",
    "APP_ADMIN_EMAIL": "full-regression@example.test",
    "APP_ADMIN_USER_ID": "42",
}


class FullPythonRegressionGateTests(unittest.TestCase):
    def test_full_mode_is_explicit_and_keeps_mandatory_default(self) -> None:
        source = SMOKE.read_text(encoding="utf-8")
        self.assertIn('FULL_MODE="${BLUEALBUM_FULL_PYTHON_REGRESSION:-0}"', source)
        self.assertIn('ARTIFACT_DIR="${M0_CI_ARTIFACT_DIR:-$ROOT_DIR/outputs/m0-ci}"', source)
        self.assertIn("validate_junit \"$API_JUNIT_FILE\" 14", source)
        self.assertIn("validate_junit \"$SELENIUM_JUNIT_FILE\" 1", source)
        self.assertIn("validate_junit \"$UI_JUNIT_FILE\" 1", source)

    def test_full_mode_has_collection_and_zero_skip_gate(self) -> None:
        source = SMOKE.read_text(encoding="utf-8")
        self.assertIn("--collect-only -q -p no:cacheprovider tests", source)
        self.assertIn("--maxfail=0 -n0", source)
        self.assertIn("validate_junit \"$FULL_JUNIT_FILE\" 148", source)
        self.assertIn("--seed-count 11", source)
        self.assertIn("--verify-admin-and-seed", source)
        self.assertIn("wait_for_gateway_api", source)
        self.assertIn("/sections?page=1&size=1", source)
        self.assertIn('AI_PROVIDER: "mock"', source)
        self.assertIn('AI_API_KEY: ""', source)
        self.assertIn('AI_BASE_URL: "http://127.0.0.1:9/disabled"', source)
        self.assertIn('rm -f -- "$FULL_CREDENTIALS_FILE"', source)

    def test_bootstrap_helper_has_no_real_model_or_credential_evidence_path(self) -> None:
        source = HELPER.read_text(encoding="utf-8")
        self.assertIn('"X-Device-Id": device_id', source)
        self.assertIn('"/admin/me"', source)
        self.assertIn('"admin"', source)
        self.assertIn('"tag": "full-regression"', source)
        self.assertIn('"threadReadbackCount"', source)
        self.assertIn('"authorId"', source)
        self.assertNotIn("AI_API_KEY", source)

        workflow = (ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
        self.assertNotIn("outputs/python-full-ci/full-regression.env", workflow)
        self.assertNotIn("outputs/python-full-ci/ci-runtime.env", workflow)

    def test_full_regression_has_no_silent_pytest_skips(self) -> None:
        test_root = ROOT / "tests"
        skipped = []
        for path in test_root.rglob("test_*.py"):
            if any(part in {".venv", ".pytest_cache", "__pycache__"} for part in path.parts):
                continue
            text = path.read_text(encoding="utf-8")
            if "pytest.skip" in text or "pytest.importorskip" in text:
                skipped.append(path.relative_to(ROOT).as_posix())
        self.assertEqual(skipped, [])

    def test_helper_rejects_http_200_admin_false_without_writing(self) -> None:
        calls: list[tuple[str, str]] = []

        def request(_base: str, method: str, path: str, **_kwargs):
            calls.append((method, path))
            if path == "/auth/login":
                return 200, {"accessToken": "offline-token"}, None
            if path == "/users/me":
                return 200, {"id": 42}, None
            if path == "/admin/me":
                return 200, {"admin": False}, None
            raise AssertionError(f"unexpected request: {method} {path}")

        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory) / "evidence.json"
            with mock.patch.object(FULL_HELPER, "_read_env", return_value=dict(PRIVATE_VALUES)):
                with mock.patch.object(FULL_HELPER, "_request", side_effect=request):
                    with self.assertRaises(FULL_HELPER.BootstrapError) as raised:
                        FULL_HELPER.verify_admin_and_seed(
                            "http://gateway/api/v1",
                            Path(directory) / "credentials.env",
                            evidence,
                            11,
                        )
            self.assertIn("admin=true", str(raised.exception))
            self.assertEqual(
                calls,
                [("POST", "/auth/login"), ("GET", "/users/me"), ("GET", "/admin/me")],
            )
            self.assertFalse(evidence.exists())
            self.assertNotIn(PRIVATE_VALUES["TEST_PASSWORD"], str(raised.exception))

    def test_helper_rejects_thread_readback_owner_mismatch(self) -> None:
        calls: list[tuple[str, str]] = []

        def request(_base: str, method: str, path: str, **kwargs):
            calls.append((method, path))
            if path == "/auth/login":
                return 200, {"accessToken": "offline-token"}, None
            if path == "/users/me":
                return 200, {"id": 42}, None
            if path == "/admin/me":
                return 200, {"admin": True}, None
            if path == "/sections":
                return 200, {"items": [{"id": 7}]}, None
            if method == "POST" and path == "/threads":
                return 201, {"id": 100}, None
            if method == "GET" and path == "/threads/100":
                return 200, {"id": 100, "authorId": 999, "title": "unexpected"}, None
            raise AssertionError(f"unexpected request: {method} {path}")

        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory) / "evidence.json"
            with mock.patch.object(FULL_HELPER, "_read_env", return_value=dict(PRIVATE_VALUES)):
                with mock.patch.object(FULL_HELPER, "_request", side_effect=request):
                    with self.assertRaises(FULL_HELPER.BootstrapError) as raised:
                        FULL_HELPER.verify_admin_and_seed(
                            "http://gateway/api/v1",
                            Path(directory) / "credentials.env",
                            evidence,
                            11,
                        )
            self.assertIn("different owner", str(raised.exception))
            self.assertEqual(
                calls,
                [
                    ("POST", "/auth/login"),
                    ("GET", "/users/me"),
                    ("GET", "/admin/me"),
                    ("GET", "/sections"),
                    ("POST", "/threads"),
                    ("GET", "/threads/100"),
                ],
            )
            self.assertFalse(evidence.exists())

    def test_helper_http_429_fails_once_without_repeating_registration(self) -> None:
        error = HTTPError(
            "http://gateway/api/v1/users/register",
            429,
            "rate limited",
            {"Retry-After": "7"},
            io.BytesIO(b"{}"),
        )
        with tempfile.TemporaryDirectory() as directory:
            credentials = Path(directory) / "credentials.env"
            with mock.patch.object(FULL_HELPER, "urlopen", side_effect=error) as opened:
                with self.assertRaises(FULL_HELPER.BootstrapError) as raised:
                    FULL_HELPER.bootstrap("http://gateway/api/v1", credentials)
            self.assertEqual(opened.call_count, 1)
            self.assertIn("HTTP 429", str(raised.exception))
            self.assertIn("Retry-After=7", str(raised.exception))
            self.assertFalse(credentials.exists())
            self.assertNotIn("Ci1!", str(raised.exception))

    def test_helper_requires_private_mode_and_evidence_excludes_credentials(self) -> None:
        identity = {
            "email": PRIVATE_VALUES["TEST_EMAIL"],
            "phone": PRIVATE_VALUES["TEST_PHONE"],
            "password": PRIVATE_VALUES["TEST_PASSWORD"],
        }
        with tempfile.TemporaryDirectory() as directory:
            credentials = Path(directory) / "credentials.env"
            with mock.patch.object(FULL_HELPER.Path, "chmod", side_effect=PermissionError("denied")):
                with self.assertRaises(FULL_HELPER.BootstrapError) as raised:
                    FULL_HELPER._write_env(credentials, identity, 42)
            self.assertIn("0600", str(raised.exception))
            self.assertFalse(credentials.exists())
            self.assertNotIn(identity["password"], str(raised.exception))

    def test_helper_evidence_records_all_readbacks_without_credentials(self) -> None:
        calls: list[tuple[str, str]] = []
        titles: dict[int, str] = {}
        next_thread_id = 100

        def request(_base: str, method: str, path: str, **kwargs):
            nonlocal next_thread_id
            calls.append((method, path))
            if path == "/auth/login":
                return 200, {"accessToken": "offline-token"}, None
            if path == "/users/me":
                return 200, {"id": 42}, None
            if path == "/admin/me":
                return 200, {"admin": True}, None
            if path == "/sections":
                return 200, {"items": [{"id": 7}]}, None
            if method == "POST" and path == "/threads":
                thread_id = next_thread_id
                next_thread_id += 1
                titles[thread_id] = str(kwargs["body"]["title"])
                return 201, {"id": thread_id}, None
            if method == "GET" and path.startswith("/threads/"):
                thread_id = int(path.rsplit("/", 1)[1])
                return 200, {"id": thread_id, "authorId": 42, "title": titles[thread_id]}, None
            raise AssertionError(f"unexpected request: {method} {path}")

        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory) / "evidence.json"
            with mock.patch.object(FULL_HELPER, "_read_env", return_value=dict(PRIVATE_VALUES)):
                with mock.patch.object(FULL_HELPER, "_request", side_effect=request):
                    FULL_HELPER.verify_admin_and_seed(
                        "http://gateway/api/v1",
                        Path(directory) / "credentials.env",
                        evidence,
                        11,
                    )
            payload = json.loads(evidence.read_text(encoding="utf-8"))
            self.assertEqual(payload["threadReadbackCount"], 11)
            self.assertEqual(payload["threadReadbackIds"], list(range(100, 111)))
            self.assertEqual(calls.count(("POST", "/threads")), 11)
            self.assertEqual(sum(method == "GET" and path.startswith("/threads/") for method, path in calls), 11)
            evidence_text = evidence.read_text(encoding="utf-8")
            for secret in (PRIVATE_VALUES["TEST_EMAIL"], PRIVATE_VALUES["TEST_PHONE"], PRIVATE_VALUES["TEST_PASSWORD"], "offline-token"):
                self.assertNotIn(secret, evidence_text)

    def test_prepare_demo_requires_loopback_and_new_sibling_outputs(self) -> None:
        calls: list[tuple[str, str]] = []
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            credentials = root / "credentials.env"
            credentials.write_text("offline", encoding="utf-8")
            accounts = root / "demo-accounts.json"
            evidence = root / "demo-evidence.json"
            with mock.patch.object(FULL_HELPER, "_request", side_effect=AssertionError("HTTP must not run")):
                with self.assertRaises(FULL_HELPER.BootstrapError) as raised:
                    FULL_HELPER.prepare_demo(
                        "https://127.0.0.1:9000/api/v1",
                        credentials,
                        accounts,
                        evidence,
                    )
            self.assertIn("loopback HTTP /api/v1", str(raised.exception))
            self.assertFalse(accounts.exists())
            self.assertFalse(evidence.exists())

            nested = root / "nested"
            nested.mkdir()
            with self.assertRaises(FULL_HELPER.BootstrapError) as raised:
                FULL_HELPER._demo_output_preflight(credentials, nested / "accounts.json", evidence)
            self.assertIn("credentials directory", str(raised.exception))
            self.assertEqual(calls, [])

    def test_prepare_demo_writes_private_accounts_and_redacted_readback(self) -> None:
        calls: list[tuple[str, str]] = []
        thread_titles: dict[int, str] = {}
        user_by_token = {"admin-token": 42, "a-token": 101, "b-token": 102}

        def request(_base: str, method: str, path: str, **kwargs):
            calls.append((method, path))
            body = kwargs.get("body") or {}
            token = kwargs.get("token")
            if method == "POST" and path == "/auth/login":
                if body["email"] == PRIVATE_VALUES["TEST_EMAIL"]:
                    return 200, {"accessToken": "admin-token"}, None
                return 200, {"accessToken": "a-token" if "demo-a-" in body["email"] else "b-token"}, None
            if method == "GET" and path == "/users/me":
                return 200, {"id": user_by_token[token], "username": "fixture-user"}, None
            if method == "GET" and path == "/admin/me":
                return 200, {"admin": True}, None
            if method == "GET" and path == "/sections":
                return 200, {"items": [{"id": 7}]}, None
            if method == "POST" and path == "/users/register":
                return 201, {"id": 101 if "demo-a-" in body["email"] else 102}, None
            if method == "GET" and path == "/governance/me":
                return 200, {"admin": False, "moderatedSectionIds": [], "postingRestricted": False}, None
            if method == "POST" and path == "/threads":
                thread_id = 201 + len(thread_titles)
                thread_titles[thread_id] = body["title"]
                return 201, {"id": thread_id}, None
            if method == "GET" and path.startswith("/threads/") and path.endswith("/posts"):
                return 200, {
                    "items": [{"id": 301, "authorId": 102, "threadId": 201, "content": "读者在演示主题下留下了一条评论。"}],
                    "page": 1,
                    "size": 50,
                    "total": 1,
                }, None
            if method == "GET" and path == "/threads/following":
                return 200, {"items": [{"id": 201}, {"id": 202}], "page": 1, "size": 20, "total": 2}, None
            if method == "GET" and path.startswith("/threads/"):
                thread_id = int(path.rsplit("/", 1)[1])
                return 200, {"id": thread_id, "authorId": 101, "title": thread_titles[thread_id]}, None
            if method == "POST" and path == "/threads/201/posts":
                return 201, {"id": 301}, None
            if method == "POST" and path == "/users/101/follow":
                return 200, {"following": True}, None
            if method == "GET" and path == "/users/101/follow-status":
                return 200, {"followedByMe": True}, None
            raise AssertionError(f"unexpected request: {method} {path}")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            credentials = root / "credentials.env"
            credentials.write_text("offline", encoding="utf-8")
            accounts = root / "demo-accounts.json"
            evidence = root / "demo-evidence.json"
            with mock.patch.object(FULL_HELPER, "_read_env", return_value=dict(PRIVATE_VALUES)):
                with mock.patch.object(FULL_HELPER, "_request", side_effect=request):
                    FULL_HELPER.prepare_demo("http://127.0.0.1:9000/api/v1", credentials, accounts, evidence)

            account_payload = json.loads(accounts.read_text(encoding="utf-8"))
            self.assertEqual(account_payload["mode"], "prepare-demo")
            self.assertEqual(set(account_payload["accounts"]), {"admin", "userA", "userB"})
            self.assertEqual(account_payload["accounts"]["admin"]["role"], "admin")
            self.assertEqual(account_payload["accounts"]["admin"]["email"], PRIVATE_VALUES["TEST_EMAIL"])
            for key in ("userA", "userB"):
                account = account_payload["accounts"][key]
                self.assertEqual(account["role"], "ordinary")
                self.assertTrue(account["username"])
                self.assertTrue(account["email"].endswith("@example.test"))
                self.assertTrue(account["phone"])
                self.assertTrue(account["password"])
            if os.name != "nt":
                self.assertEqual(accounts.stat().st_mode & 0o777, 0o600)

            evidence_text = evidence.read_text(encoding="utf-8")
            evidence_payload = json.loads(evidence_text)
            self.assertEqual(evidence_payload["counts"]["threads"], 2)
            self.assertEqual(evidence_payload["counts"]["comments"], 1)
            self.assertEqual(evidence_payload["counts"]["followerFeedMatches"], 2)
            self.assertEqual(len(evidence_payload["cases"]), 7)
            for secret in (
                PRIVATE_VALUES["TEST_EMAIL"],
                PRIVATE_VALUES["TEST_PHONE"],
                PRIVATE_VALUES["TEST_PASSWORD"],
                "admin-token",
                "a-token",
                "b-token",
            ):
                self.assertNotIn(secret, evidence_text)
            self.assertEqual(calls.count(("POST", "/users/register")), 2)
            self.assertEqual(calls.count(("POST", "/threads")), 2)
            self.assertIn(("GET", "/threads/following"), calls)


if __name__ == "__main__":
    unittest.main()
