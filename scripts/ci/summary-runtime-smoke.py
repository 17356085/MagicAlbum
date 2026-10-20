#!/usr/bin/env python3
"""M4B controlled-provider runtime acceptance gate.

This gate reuses the existing M4A/M3 Compose lifecycle and HTTP helpers, then
adds one disposable ``python:3.13-alpine`` provider fixture.  The fixture is
an OpenAI-compatible protocol simulator, so ``externalModelProven`` is always
false in the evidence produced here.
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import importlib.util
import json
import re
import secrets
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any, Callable, Iterable


ROOT_DIR = Path(__file__).resolve().parents[2]
COMMUNITY_SCRIPT = Path(__file__).with_name("community-runtime-smoke.py")
COMMUNITY_SPEC = importlib.util.spec_from_file_location("bluealbum_community_runtime", COMMUNITY_SCRIPT)
if COMMUNITY_SPEC is None or COMMUNITY_SPEC.loader is None:  # pragma: no cover
    raise ImportError(f"cannot load existing runtime gate: {COMMUNITY_SCRIPT}")
COMMUNITY = importlib.util.module_from_spec(COMMUNITY_SPEC)
sys.modules[COMMUNITY_SPEC.name] = COMMUNITY
COMMUNITY_SPEC.loader.exec_module(COMMUNITY)

BaseRuntime = COMMUNITY.ComposeRuntime
BaseAcceptance = COMMUNITY.CommunityAcceptance
BaseHttpApi = COMMUNITY.BaseHttpApi
CaseResult = COMMUNITY.CaseResult
GateFailure = COMMUNITY.GateFailure
Identity = COMMUNITY.Identity
safe_text = COMMUNITY.safe_text
sql_literal = COMMUNITY.sql_literal

API_ROOT = "/api/v1"
FIXTURE_PRIVATE_PORT = 8088
FIXTURE_IMAGE = "python:3.13-alpine"
FIXTURE_MODES = ("normal", "delay", "rate-limit-first", "upstream", "auth", "invalid", "no-usage", "empty", "oversize")
PLANNED_API_CASES = (
    "auth-and-explicit-only",
    "normal-and-usage-readback",
    "long-input-complete",
    "idempotency-and-duplicate-delivery",
    "scope-revocation-and-hidden",
    "budget-exhaustion",
    "provider-failure-and-retry",
    "timeout-and-recovery",
    "cancel-and-late-result",
    "content-version-late-result",
    "restart-durable-and-mq-outage",
)
PLANNED_BROWSER_CASES = (
    "browser-summary-mobile-day",
    "browser-summary-landscape-day",
    "browser-summary-mobile-night",
    "browser-summary-landscape-night",
)
PLANNED_CASES = ("bootstrap", "build", "start", *PLANNED_API_CASES, *PLANNED_BROWSER_CASES)


def utc_now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


class SummaryReporter(COMMUNITY.M4Reporter):
    def write_junit(self, path: Path) -> None:
        failed = sum(case.status == "failed" for case in self.cases)
        errors = sum(case.status == "error" for case in self.cases)
        skipped = sum(case.status == "skipped" for case in self.cases)
        suite = ET.Element(
            "testsuite",
            {
                "name": "bluealbum-m4b-summary-runtime",
                "tests": str(len(self.cases)),
                "failures": str(failed),
                "errors": str(errors),
                "skipped": str(skipped),
            },
        )
        for case in self.cases:
            node = ET.SubElement(suite, "testcase", {"name": case.name, "time": f"{case.duration_seconds:.3f}"})
            if case.status == "failed":
                ET.SubElement(node, "failure", {"message": case.detail or "acceptance failure"}).text = case.detail
            elif case.status == "error":
                ET.SubElement(node, "error", {"message": case.detail or "unexpected error"}).text = case.detail
            elif case.status == "skipped":
                ET.SubElement(node, "skipped")
        path.write_bytes(ET.tostring(suite, encoding="utf-8", xml_declaration=True))


class ComposeRuntime(BaseRuntime):
    """M4A runtime plus a loopback-only controlled provider container."""

    def __init__(self, artifact_parent: Path) -> None:
        super().__init__(artifact_parent)
        old_project = self.project
        if not old_project.startswith("bluealbum-m4-"):
            raise GateFailure("unexpected inherited Compose project prefix")
        new_project = old_project.replace("bluealbum-m4-", "bluealbum-m4b-", 1)
        old_artifact = self.artifact_dir
        new_artifact = artifact_parent / new_project
        if new_artifact.exists():
            raise GateFailure("generated M4B artifact directory already exists")
        old_artifact.rename(new_artifact)
        self.project = new_project
        self.artifact_dir = new_artifact
        self.log_dir = new_artifact / "logs"
        self.screenshot_dir = new_artifact / "screenshots"
        self.env_file = new_artifact / "runtime.env"
        self.override_file = new_artifact / "runtime.override.yml"
        self.checks_file = new_artifact / "checks.json"
        self.junit_file = new_artifact / "junit.xml"

    def _write_env(self, public_base_url: str) -> None:
        super()._write_env(public_base_url)
        self.env_values.update(
            {
                "AI_PROVIDER": "real",
                "AI_API_KEY": "test-only",
                "AI_BASE_URL": "http://summary-fixture:8088/v1",
                "AI_MODEL": "controlled-summary",
                "AI_REQUEST_TIMEOUT_SECONDS": "5",
                "APP_AI_SUMMARY_DAILY_TOKEN_BUDGET": "30000",
                "APP_AI_SUMMARY_MAX_OUTPUT_TOKENS": "512",
            }
        )
        self.env_file.write_text(
            "\n".join(f"{key}={value}" for key, value in self.env_values.items()) + "\n",
            encoding="utf-8",
            newline="\n",
        )
        try:
            self.env_file.chmod(0o600)
        except OSError:
            pass

    def _write_override(self) -> None:
        super()._write_override()
        text = self.override_file.read_text(encoding="utf-8")
        needle = "      S3_PUBLIC_BASE_URL: ${S3_PUBLIC_BASE_URL}\n"
        if needle not in text:
            raise GateFailure("inherited backend override lost its environment merge point")
        text = text.replace(
            needle,
            needle
            + "      AI_PROVIDER: ${AI_PROVIDER}\n"
            + "      AI_API_KEY: ${AI_API_KEY}\n"
            + "      AI_BASE_URL: ${AI_BASE_URL}\n"
            + "      AI_MODEL: ${AI_MODEL}\n"
            + "      AI_REQUEST_TIMEOUT_SECONDS: ${AI_REQUEST_TIMEOUT_SECONDS}\n"
            + "      APP_AI_SUMMARY_DAILY_TOKEN_BUDGET: ${APP_AI_SUMMARY_DAILY_TOKEN_BUDGET}\n"
            + "      APP_AI_SUMMARY_MAX_OUTPUT_TOKENS: ${APP_AI_SUMMARY_MAX_OUTPUT_TOKENS}\n",
            1,
        )
        fixture = f'''
  summary-fixture:
    image: {FIXTURE_IMAGE}
    command: ["python", "/fixture/summary-provider-fixture.py", "--host", "0.0.0.0", "--port", "{FIXTURE_PRIVATE_PORT}"]
    ports: !override
      - "127.0.0.1:0:{FIXTURE_PRIVATE_PORT}"
    volumes:
      - ./scripts/ci/summary-provider-fixture.py:/fixture/summary-provider-fixture.py:ro
    read_only: true
    tmpfs:
      - /tmp
    user: "65532:65532"
    cap_drop: [ALL]
    security_opt: [no-new-privileges:true]
    mem_limit: 128m
    memswap_limit: 128m
    healthcheck:
      test: ["CMD", "python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:{FIXTURE_PRIVATE_PORT}/health', timeout=2)"]
      interval: 2s
      timeout: 3s
      retries: 20
'''
        marker = "\n\nvolumes:\n"
        if marker not in text:
            raise GateFailure("inherited Compose override has no volumes section")
        text = text.replace(marker, "\n" + fixture + marker, 1)
        self.override_file.write_text(text, encoding="utf-8", newline="\n")

    @staticmethod
    def _rows(inventory: str) -> list[dict[str, Any]]:
        if inventory.lstrip().startswith("["):
            value = json.loads(inventory)
            return value if isinstance(value, list) else []
        return [json.loads(line) for line in inventory.splitlines() if line.strip()]

    def refresh_ports(self) -> dict[str, int]:
        ports = super().refresh_ports()
        inventory = self.compose("ps", "--all", "--format", "json", timeout=30)
        running = {row.get("Service") for row in self._rows(inventory) if row.get("State") == "running"}
        if "summary-fixture" in running:
            output = self.compose("port", "--index", "1", "summary-fixture", str(FIXTURE_PRIVATE_PORT), timeout=30)
            match = re.search(r":(\d+)\s*$", output)
            if not match:
                raise GateFailure("could not read mapped provider fixture port")
            port = int(match.group(1))
            if not 1 <= port <= 65535:
                raise GateFailure("provider fixture host port was invalid")
            ports["summary-fixture"] = port
        self.ports = ports
        return ports

    def start(self) -> None:
        self.compose("up", "-d", "summary-fixture", timeout=180)
        self.refresh_ports()
        if "summary-fixture" not in self.ports:
            raise GateFailure("provider fixture did not receive a dynamic host port")
        self.wait_http(f"http://127.0.0.1:{self.ports['summary-fixture']}/health", {200}, timeout=90)
        super().start()


class SummaryAcceptance(BaseAcceptance):
    def __init__(self, runtime: ComposeRuntime, browser_timeout: float = 90.0) -> None:
        super().__init__(runtime, browser_timeout=browser_timeout)
        self.runtime: ComposeRuntime = runtime
        self.fixture = ""
        self.owner: Identity | None = None
        self.other: Identity | None = None
        self.admin2: Identity | None = None
        self.section_id: int | None = None
        self.thread_id: int | None = None
        self.summary_actors: dict[str, Identity] = {}
        self.readback: dict[str, Any] = {"quotaWaits": [], "tasks": {}}

    def refresh_urls(self) -> None:
        super().refresh_urls()
        port = self.runtime.ports.get("summary-fixture")
        if not port:
            raise GateFailure("provider fixture host port was not discovered")
        self.fixture = f"http://127.0.0.1:{port}"

    def _fixture_request(self, method: str, path: str, **kwargs: Any) -> tuple[Any, Any]:
        api = BaseHttpApi(self.fixture)
        try:
            response = api.request(method, path, **kwargs)
            try:
                value = response.json()
            except Exception:
                value = None
            return response, value
        finally:
            api.close()

    def fixture_state(self) -> dict[str, Any]:
        response, value = self._fixture_request("GET", "/control/state")
        if response.status_code != 200 or not isinstance(value, dict):
            raise GateFailure("provider fixture state readback failed")
        records = value.get("records")
        if not isinstance(records, list) or not isinstance(value.get("calls"), int):
            raise GateFailure("provider fixture state omitted bounded call evidence")
        return value

    def reset_fixture(self, mode: str = "normal") -> None:
        if mode not in FIXTURE_MODES:
            raise GateFailure("unknown fixture mode")
        response, _ = self._fixture_request("POST", "/control/reset")
        if response.status_code != 200:
            raise GateFailure("provider fixture reset failed")
        response, _ = self._fixture_request("POST", "/control/mode", json={"mode": mode})
        if response.status_code != 200:
            raise GateFailure("provider fixture mode update failed")

    def wait_fixture_calls(self, count: int, timeout: float = 20.0) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        state = self.fixture_state()
        while int(state.get("calls", 0)) < count and time.monotonic() < deadline:
            time.sleep(0.25)
            state = self.fixture_state()
        if int(state.get("calls", 0)) < count:
            raise GateFailure(f"provider fixture did not observe {count} calls")
        return state

    def _setup(self) -> None:
        if self.owner is not None:
            return
        self.refresh_urls()
        self.owner = self.register_login("owner")
        self.other = self.register_login("other")
        self.runtime.set_admin_email(self.owner.email)
        self.runtime.restart_backend_with_admin_email()
        self.refresh_urls()
        access = self.governance(self.owner)
        if access.get("admin") is not True:
            raise GateFailure("configured owner did not receive the persisted administrator role")
        response, body = self._api_request("GET", "/sections", self.owner, params={"page": 1, "size": 20})
        self._status(response, 200, "public sections")
        rows = self._dict(body, "public sections").get("items")
        if not isinstance(rows, list):
            raise GateFailure("public sections omitted items")
        candidates = [row for row in rows if isinstance(row, dict) and not row.get("archived") and row.get("id")]
        if not candidates:
            raise GateFailure("no writable seeded section was available")
        self.section_id = int(candidates[0]["id"])

    def create_summary_thread(self, identity: Identity, content: str, label: str) -> int:
        self._setup()
        assert self.section_id is not None
        response, body = self._api_request(
            "POST",
            "/threads",
            identity,
            json={"sectionId": self.section_id, "title": f"M4B {label}", "content": content, "tags": ["m4b"]},
        )
        self._status(response, 201, f"create summary thread {label}")
        value = self._dict(body, f"create summary thread {label}")
        if not isinstance(value.get("id"), int):
            raise GateFailure("summary thread response omitted integer id")
        return int(value["id"])

    def summary_actor(self, label: str) -> Identity:
        """Create one independent author per summary case.

        The application deliberately limits AI starts to ten per user-minute.
        Keeping each paid/protocol scenario on its own generated author makes
        the gate observe that production protection instead of exhausting one
        actor while still leaving every real 429 visible to the case.
        """

        self._setup()
        actor = self.summary_actors.get(label)
        if actor is None:
            actor = self.register_login(f"summary-{label}")
            self.summary_actors[label] = actor
            self.readback.setdefault("actorIsolation", {})[label] = {"userId": actor.user_id, "quotaWaitSeconds": 0}
        return actor

    def summary_request(self, method: str, path: str, identity: Identity | None, key: str | None = None, **kwargs: Any) -> tuple[Any, Any]:
        headers = dict(kwargs.pop("headers", {}) or {})
        if key is not None:
            headers["Idempotency-Key"] = key
        return self._api_request(method, path, identity, headers=headers, **kwargs)

    def start_summary(self, identity: Identity, thread_id: int, key: str, force: bool = False) -> tuple[Any, dict[str, Any]]:
        response, body = self.summary_request("POST", f"/ai/summary/{thread_id}", identity, key, params={"force": str(force).lower()})
        if response.status_code == 429:
            self.readback.setdefault("quotaWaits", []).append(
                {"actorId": identity.user_id, "threadId": thread_id, "waitSeconds": 0, "retried": False}
            )
        return response, self._dict(body, "start summary") if isinstance(body, dict) else {}

    def audit_task(self, task_id: str, label: str) -> None:
        """Persist bounded SQL evidence without summary bodies or idempotency keys."""

        task = self.sql_one(
            f"SELECT task_id,thread_id,content_version,triggered_by,status,attempts,error_code,input_chars,request_id FROM ai_summary_task WHERE task_id={sql_literal(task_id)}",
            f"{label} task audit",
        )
        attempts = self.runtime.sql(
            f"SELECT attempt_number,status,provider,model,response_model,provider_request_id,provider_response_id,input_tokens,output_tokens,usage_estimated,reserved_tokens,charged_tokens,duration_millis,error_code FROM ai_summary_attempt WHERE task_id={sql_literal(task_id)} ORDER BY attempt_number"
        )
        requests = self.runtime.sql(
            f"SELECT task_id,thread_id,content_version,force_requested FROM ai_summary_request WHERE task_id={sql_literal(task_id)} ORDER BY created_at"
        )
        budgets = self.runtime.sql(
            f"SELECT budget_day,charged_tokens FROM ai_summary_budget WHERE user_id={task[3]} ORDER BY budget_day"
        )
        self.readback.setdefault("tasks", {})[task_id] = {
            "label": label,
            "task": {
                "taskId": task[0],
                "threadId": int(task[1]),
                "contentVersion": int(task[2]),
                "triggeredBy": int(task[3]),
                "status": task[4],
                "attempts": int(task[5]),
                "errorCode": task[6],
                "inputChars": int(task[7]),
                "requestId": task[8],
            },
            "attempts": [
                {
                    "attemptNumber": int(row[0]),
                    "status": row[1],
                    "provider": row[2],
                    "model": row[3],
                    "responseModel": row[4],
                    "providerRequestId": row[5],
                    "providerResponseId": row[6],
                    "inputTokens": None if row[7] in {"", None} else int(row[7]),
                    "outputTokens": None if row[8] in {"", None} else int(row[8]),
                    "usageEstimated": row[9] in {"1", 1, True},
                    "reservedTokens": int(row[10]),
                    "chargedTokens": int(row[11]),
                    "durationMillis": int(row[12]),
                    "errorCode": row[13],
                }
                for row in attempts
            ],
            "requests": [
                {
                    "taskId": row[0],
                    "threadId": int(row[1]),
                    "contentVersion": int(row[2]),
                    "forceRequested": row[3] in {"1", 1, True},
                }
                for row in requests
            ],
            "budgets": [{"budgetDay": row[0], "chargedTokens": int(row[1])} for row in budgets],
        }

    def read_summary(self, identity: Identity, thread_id: int) -> tuple[Any, dict[str, Any]]:
        response, body = self.summary_request("GET", f"/ai/summary/{thread_id}", identity)
        return response, self._dict(body, "read summary") if isinstance(body, dict) else {}

    def read_task(self, identity: Identity, task_id: str) -> tuple[Any, dict[str, Any]]:
        response, body = self.summary_request("GET", f"/ai/summary/tasks/{task_id}", identity)
        return response, self._dict(body, "read summary task") if isinstance(body, dict) else {}

    def wait_terminal(self, identity: Identity, task_id: str, timeout: float = 100.0) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        last: dict[str, Any] = {}
        while time.monotonic() < deadline:
            response, last = self.read_task(identity, task_id)
            if response.status_code != 200:
                raise GateFailure(f"summary task read returned {response.status_code}")
            if last.get("status") in {"COMPLETED", "FAILED", "CANCELLED", "SUPERSEDED"}:
                return last
            time.sleep(0.5)
        raise GateFailure(f"summary task did not reach a terminal state: {last.get('status')}")

    def sql_count(self, query: str, label: str) -> int:
        rows = self.runtime.sql(query)
        if len(rows) != 1 or len(rows[0]) != 1:
            raise GateFailure(f"{label} returned an unexpected SQL shape")
        return int(rows[0][0])

    def sql_one(self, query: str, label: str) -> list[str]:
        rows = self.runtime.sql(query)
        if len(rows) != 1:
            raise GateFailure(f"{label} returned {len(rows)} rows")
        return rows[0]

    def auth_and_explicit_only(self) -> None:
        self._setup()
        assert self.owner is not None
        self.reset_fixture()
        self.thread_id = self.create_summary_thread(self.owner, "M4B explicit-only", "explicit-only")
        response, _ = self.summary_request("GET", f"/ai/summary/{self.thread_id}", None)
        self._status(response, 401, "anonymous summary read")
        response, _ = self.summary_request("POST", f"/ai/summary/{self.thread_id}", None, str(__import__("uuid").uuid4()))
        self._status(response, 401, "anonymous summary start")
        response, summary = self.read_summary(self.owner, self.thread_id)
        self._status(response, 200, "initial summary read")
        if summary.get("status") != "IDLE" or summary.get("taskId") is not None or summary.get("summary") != "":
            raise GateFailure("ordinary thread creation exposed an active or derived summary")
        if self.fixture_state().get("calls") != 0:
            raise GateFailure("ordinary thread creation called the provider")
        if self.sql_count("SELECT COUNT(*) FROM ai_summary_task", "initial summary task count") != 0:
            raise GateFailure("ordinary thread creation inserted a summary task")
        if self.other is None:
            raise GateFailure("ordinary reader identity was not initialized")
        response, reader_summary = self.summary_request("GET", f"/ai/summary/{self.thread_id}", self.other)
        self._status(response, 200, "reader summary read")
        if reader_summary.get("canManage") is not False:
            raise GateFailure("ordinary reader unexpectedly received summary manage permission")
        response, _ = self.summary_request(
            "POST", f"/ai/summary/{self.thread_id}", self.other, str(__import__("uuid").uuid4())
        )
        self._status(response, 403, "non-author summary start")
        response, _ = self.summary_request("POST", f"/ai/summary/{self.thread_id}", self.owner)
        self._status(response, 400, "missing idempotency key")
        response, _ = self.summary_request("POST", f"/ai/summary/{self.thread_id}", self.owner, "not-a-uuid")
        self._status(response, 400, "invalid idempotency key")
        if self.fixture_state().get("calls") != 0 or self.sql_count("SELECT COUNT(*) FROM ai_summary_task", "explicit-only task count") != 0:
            raise GateFailure("unauthorized or invalid summary requests created work")

    def normal_and_usage_readback(self) -> None:
        self._setup()
        actor = self.summary_actor("normal")
        self.reset_fixture()
        thread_id = self.create_summary_thread(actor, "M4B normal usage marker", "normal")
        key = str(__import__("uuid").uuid4())
        response, started = self.start_summary(actor, thread_id, key)
        self._status(response, 200, "normal summary start")
        task_id = started.get("taskId")
        if not isinstance(task_id, str):
            raise GateFailure("normal summary start omitted taskId")
        result = self.wait_terminal(actor, task_id)
        if result.get("status") != "COMPLETED" or not result.get("summary"):
            raise GateFailure("normal controlled summary did not complete")
        if result.get("provider") != "summary-fixture" or result.get("model") != "controlled-summary":
            raise GateFailure("summary response did not expose configured provider/request model")
        if not isinstance(result.get("inputTokens"), int) or not isinstance(result.get("outputTokens"), int):
            raise GateFailure("summary response omitted actual usage")
        if result.get("usageEstimated") is not False or result.get("chargedTokens", 0) <= 0:
            raise GateFailure("actual usage did not settle the budget evidence")
        state = self.fixture_state()
        if state.get("calls") != 1 or len(state.get("records", [])) != 1:
            raise GateFailure("normal summary did not produce exactly one fixture call")
        response, api_summary = self.read_summary(actor, thread_id)
        self._status(response, 200, "normal summary API readback")
        if api_summary.get("summary") != result.get("summary") or api_summary.get("status") != "COMPLETED":
            raise GateFailure("normal task/API summary readback diverged")
        task = self.sql_one(
            f"SELECT status,attempts,error_code,summary,content_version,thread_id,triggered_by,request_id FROM ai_summary_task WHERE task_id={sql_literal(task_id)}",
            "summary task readback",
        )
        attempt = self.sql_one(
            f"SELECT status,provider,model,response_model,provider_request_id,provider_response_id,input_tokens,output_tokens,usage_estimated FROM ai_summary_attempt WHERE task_id={sql_literal(task_id)} ORDER BY attempt_number DESC LIMIT 1",
            "summary attempt readback",
        )
        thread = self.sql_one(
            f"SELECT summary_task_id,summary_status,summary,content_version FROM threads WHERE id={thread_id}",
            "thread summary readback",
        )
        request = self.sql_one(
            f"SELECT task_id,thread_id,content_version,force_requested FROM ai_summary_request WHERE triggered_by={actor.user_id} AND request_key={sql_literal(key)}",
            "summary request readback",
        )
        if task[0] != "COMPLETED" or attempt[0] != "COMPLETED" or thread[0] != task_id or request[0] != task_id:
            raise GateFailure("summary task/attempt/thread/request SQL linkage was inconsistent")
        if task[3] != result.get("summary") or thread[2] != task[3] or api_summary.get("summary") != thread[2]:
            raise GateFailure("task/thread/API summary bodies were not identical")
        if int(task[4]) != int(thread[3]) or int(api_summary.get("contentVersion", -1)) != int(thread[3]):
            raise GateFailure("summary task/thread/API content versions were not identical")
        if (
            attempt[3] != "controlled-summary-response"
            or not attempt[4].startswith("fixture-request-")
            or not attempt[5].startswith("fixture-response-")
            or int(attempt[6]) < 0
            or int(attempt[7]) < 0
            or attempt[8] not in {"0", 0}
        ):
            raise GateFailure("summary attempt SQL omitted provider response IDs/model or actual non-negative usage")
        attempt_sum = self.sql_one(
            f"SELECT COALESCE(SUM(charged_tokens),0) FROM ai_summary_attempt WHERE task_id={sql_literal(task_id)}",
            "summary attempt budget sum",
        )
        budget = self.sql_one(
            f"SELECT charged_tokens FROM ai_summary_budget WHERE user_id={actor.user_id} AND budget_day={sql_literal(utc_now().date().isoformat())}",
            "summary budget readback",
        )
        if int(budget[0]) < int(attempt_sum[0]) or int(attempt_sum[0]) != int(result.get("chargedTokens", -1)):
            raise GateFailure("summary attempt charges and actor budget ledger were inconsistent")
        self.audit_task(task_id, "normal")
        self.readback["normal"] = {
            "taskId": task_id,
            "threadId": thread_id,
            "fixtureCalls": state.get("calls"),
            "attemptStatus": attempt[0],
            "contentVersion": int(thread[3]),
            "budgetChargedTokens": int(budget[0]),
            "attemptChargedTokens": int(attempt_sum[0]),
            "usageEstimated": result.get("usageEstimated"),
            "inputTokens": result.get("inputTokens"),
            "outputTokens": result.get("outputTokens"),
        }

    def long_input_complete(self) -> None:
        self._setup()
        actor = self.summary_actor("long-input")
        self.reset_fixture()
        content = "长" * 9000
        thread_id = self.create_summary_thread(actor, content, "long-input")
        response, started = self.start_summary(actor, thread_id, str(__import__("uuid").uuid4()))
        self._status(response, 200, "long input summary start")
        task_id = started.get("taskId")
        if not isinstance(task_id, str):
            raise GateFailure("long input summary omitted taskId")
        result = self.wait_terminal(actor, task_id)
        if result.get("status") != "COMPLETED":
            raise GateFailure("9000-character summary did not complete")
        state = self.fixture_state()
        if state.get("calls") != 1 or state["records"][0].get("inputChars") != 9000:
            raise GateFailure("fixture did not observe the complete 9000-character input")
        self.audit_task(task_id, "long-input")

    def publish_duplicate(self, task_id: str) -> None:
        api = BaseHttpApi(self.management)
        try:
            response = api.request(
                "POST",
                "/api/exchanges/%2F/amq.default/publish",
                auth=(self.runtime.env_values["RABBITMQ_USER"], self.runtime.secrets["RABBITMQ_PASSWORD"]),
                json={"properties": {}, "routing_key": "ai.summary.v1", "payload": task_id, "payload_encoding": "string"},
            )
            if response.status_code != 200 or not response.json().get("routed"):
                raise GateFailure("RabbitMQ duplicate delivery was not routed")
        finally:
            api.close()

    def idempotency_and_duplicate_delivery(self) -> None:
        self._setup()
        actor = self.summary_actor("idempotency")
        self.reset_fixture()
        thread_id = self.create_summary_thread(actor, "M4B idempotency", "idempotency")
        key = str(__import__("uuid").uuid4())
        response, first = self.start_summary(actor, thread_id, key)
        self._status(response, 200, "first idempotent start")
        task_id = first.get("taskId")
        if not isinstance(task_id, str):
            raise GateFailure("idempotent start omitted taskId")
        self.wait_terminal(actor, task_id)
        response, second = self.start_summary(actor, thread_id, key)
        self._status(response, 200, "idempotent replay")
        if second.get("taskId") != task_id:
            raise GateFailure("same idempotency key did not return the original task")
        before = self.sql_count(f"SELECT COUNT(*) FROM ai_summary_attempt WHERE task_id={sql_literal(task_id)}", "attempt count before duplicate")
        calls = self.fixture_state().get("calls")
        self.publish_duplicate(task_id)
        time.sleep(2)
        after = self.sql_count(f"SELECT COUNT(*) FROM ai_summary_attempt WHERE task_id={sql_literal(task_id)}", "attempt count after duplicate")
        if after != before or self.fixture_state().get("calls") != calls:
            raise GateFailure("duplicate delivery started a second paid attempt")
        requests = self.sql_count(
            f"SELECT COUNT(*) FROM ai_summary_request WHERE triggered_by={actor.user_id} AND request_key={sql_literal(key)}",
            "idempotency request bindings",
        )
        if requests != 1:
            raise GateFailure("idempotency binding was not unique")
        self.audit_task(task_id, "idempotency")

    def scope_revocation_and_hidden(self) -> None:
        self._setup()
        assert self.owner is not None and self.other is not None
        reader = self.summary_actor("scope-reader")
        self.reset_fixture("delay")
        if self.admin2 is None:
            self.admin2 = self.register_login("admin2")
            self.manage_user(self.owner, self.admin2.user_id, "admin", {"enabled": True, "reason": "M4B second administrator"}, "grant second admin")
        thread_id = self.create_summary_thread(self.other, "M4B revoke while processing", "revoke")
        response, started = self.start_summary(self.owner, thread_id, str(__import__("uuid").uuid4()))
        self._status(response, 200, "revocation start")
        task_id = started.get("taskId")
        if not isinstance(task_id, str):
            raise GateFailure("revocation task omitted id")
        response, _ = self.summary_request("POST", f"/ai/summary/{thread_id}", reader, str(__import__("uuid").uuid4()))
        self._status(response, 403, "ordinary non-author summary start")
        self.wait_fixture_calls(1)
        self.manage_user(self.admin2, self.owner.user_id, "admin", {"enabled": False, "reason": "M4B current scope revocation"}, "revoke owner")
        result = self.wait_terminal(self.admin2, task_id)
        if result.get("status") != "FAILED" or result.get("errorCode") != "PERMISSION_REVOKED":
            raise GateFailure("revoked owner task was not terminal PERMISSION_REVOKED")
        self.manage_user(self.admin2, self.owner.user_id, "admin", {"enabled": True, "reason": "M4B restore acceptance actor"}, "restore owner")

        self.reset_fixture("delay")
        hidden_thread = self.create_summary_thread(self.other, "M4B hidden late result", "hidden")
        response, started = self.start_summary(self.owner, hidden_thread, str(__import__("uuid").uuid4()))
        self._status(response, 200, "hidden start")
        hidden_task = started.get("taskId")
        if not isinstance(hidden_task, str):
            raise GateFailure("hidden task omitted id")
        self.wait_fixture_calls(1)
        report = self.create_report(self.owner, "THREAD", hidden_thread, "M4B hide")
        response, body = self._api_request(
            "POST",
            f"/admin/reports/{report['id']}/resolve",
            self.admin2,
            json={"action": "HIDE", "reason": "M4B hidden late-result check"},
        )
        self._status(response, 200, "hide thread")
        deadline = time.monotonic() + 30
        row: list[Any] = []
        while time.monotonic() < deadline:
            row = self.sql_one(
                f"SELECT status,summary FROM ai_summary_task WHERE task_id={sql_literal(hidden_task)}",
                "hidden task SQL",
            )
            if row[0] in {"COMPLETED", "FAILED", "CANCELLED", "SUPERSEDED"}:
                break
            time.sleep(0.5)
        if not row or row[0] != "SUPERSEDED":
            raise GateFailure("hidden thread did not supersede its active summary task")
        pointer = self.sql_one(f"SELECT status,summary_task_id,summary_status,summary FROM threads WHERE id={hidden_thread}", "hidden thread SQL")
        if row[0] != "SUPERSEDED" or row[1] or pointer[0] != "HIDDEN" or pointer[1] or pointer[2] != "IDLE" or pointer[3]:
            raise GateFailure("hidden content retained or accepted a late summary")
        response, _ = self.read_summary(reader, hidden_thread)
        self._status(response, 404, "hidden current summary read")
        response, _ = self.read_task(reader, hidden_task)
        self._status(response, 404, "hidden historical summary read")
        self.audit_task(task_id, "scope-revoked")
        self.audit_task(hidden_task, "scope-hidden")

    def budget_exhaustion(self) -> None:
        self._setup()
        actor = self.summary_actor("budget")
        self.reset_fixture()
        thread_id = self.create_summary_thread(actor, "额" * 10000, "budget")
        response, started = self.start_summary(actor, thread_id, str(__import__("uuid").uuid4()))
        self._status(response, 200, "budget start")
        task_id = started.get("taskId")
        if not isinstance(task_id, str):
            raise GateFailure("budget task omitted id")
        result = self.wait_terminal(actor, task_id, timeout=30)
        if result.get("status") != "FAILED" or result.get("errorCode") != "BUDGET_EXCEEDED":
            raise GateFailure("oversized valid input did not produce BUDGET_EXCEEDED")
        if self.fixture_state().get("calls") != 0:
            raise GateFailure("budget rejection called the provider")
        self.audit_task(task_id, "budget")

    def provider_failure_and_retry(self) -> None:
        self._setup()
        actor = self.summary_actor("provider-retry")
        self.reset_fixture("rate-limit-first")
        thread_id = self.create_summary_thread(actor, "M4B rate limit retry", "rate-limit")
        response, started = self.start_summary(actor, thread_id, str(__import__("uuid").uuid4()))
        self._status(response, 200, "rate limit start")
        task_id = started.get("taskId")
        if not isinstance(task_id, str):
            raise GateFailure("rate-limit task omitted id")
        result = self.wait_terminal(actor, task_id, timeout=40)
        if result.get("status") != "COMPLETED" or self.fixture_state().get("calls") != 2:
            raise GateFailure("429 retry did not complete with exactly two fixture calls")
        attempts = self.sql_count(f"SELECT COUNT(*) FROM ai_summary_attempt WHERE task_id={sql_literal(task_id)}", "rate-limit attempts")
        if attempts != 2:
            raise GateFailure("429 retry did not persist two attempts")
        self.audit_task(task_id, "provider-rate-limit")

        self.reset_fixture("auth")
        auth_actor = self.summary_actor("provider-auth")
        auth_thread = self.create_summary_thread(auth_actor, "M4B auth failure", "auth")
        response, started = self.start_summary(auth_actor, auth_thread, str(__import__("uuid").uuid4()))
        self._status(response, 200, "auth failure start")
        auth_task = started.get("taskId")
        if not isinstance(auth_task, str):
            raise GateFailure("auth task omitted id")
        result = self.wait_terminal(auth_actor, auth_task, timeout=30)
        if result.get("status") != "FAILED" or result.get("errorCode") != "AUTH" or self.fixture_state().get("calls") != 1:
            raise GateFailure("401 auth failure was retried or misclassified")
        self.audit_task(auth_task, "provider-auth")

    def timeout_and_recovery(self) -> None:
        self._setup()
        actor = self.summary_actor("timeout")
        self.reset_fixture("delay")
        thread_id = self.create_summary_thread(actor, "M4B timeout recovery", "timeout")
        response, started = self.start_summary(actor, thread_id, str(__import__("uuid").uuid4()))
        self._status(response, 200, "timeout start")
        task_id = started.get("taskId")
        if not isinstance(task_id, str):
            raise GateFailure("timeout task omitted id")
        self.wait_fixture_calls(1)
        response, _ = self._fixture_request("POST", "/control/mode", json={"mode": "normal"})
        if response.status_code != 200:
            raise GateFailure("fixture did not switch to normal recovery mode")
        result = self.wait_terminal(actor, task_id, timeout=40)
        if result.get("status") != "COMPLETED" or self.fixture_state().get("calls") != 2:
            raise GateFailure("timeout recovery did not retry and complete")
        self.audit_task(task_id, "timeout")

    def cancel_and_late_result(self) -> None:
        self._setup()
        actor = self.summary_actor("cancel")
        self.reset_fixture("delay")
        thread_id = self.create_summary_thread(actor, "M4B cancellation", "cancel")
        response, started = self.start_summary(actor, thread_id, str(__import__("uuid").uuid4()))
        self._status(response, 200, "cancel start")
        task_id = started.get("taskId")
        if not isinstance(task_id, str):
            raise GateFailure("cancel task omitted id")
        self.wait_fixture_calls(1)
        response, cancelled = self.summary_request("POST", f"/ai/summary/{thread_id}/tasks/{task_id}/cancel", actor)
        self._status(response, 200, "cancel summary")
        if cancelled.get("status") != "CANCELLED":
            raise GateFailure("cancel endpoint did not persist CANCELLED")
        time.sleep(11)
        response, final = self.read_task(actor, task_id)
        self._status(response, 200, "cancel late read")
        if final.get("status") != "CANCELLED" or final.get("summary"):
            raise GateFailure("late provider result overwrote a cancelled task")
        task_row = self.sql_one(
            f"SELECT status,attempts,error_code,summary FROM ai_summary_task WHERE task_id={sql_literal(task_id)}",
            "cancelled task SQL",
        )
        attempt_row = self.sql_one(
            f"SELECT status,finished_at,reserved_tokens,charged_tokens,usage_estimated FROM ai_summary_attempt WHERE task_id={sql_literal(task_id)} ORDER BY attempt_number DESC LIMIT 1",
            "cancelled attempt SQL",
        )
        attempt_sum = self.sql_one(
            f"SELECT COALESCE(SUM(charged_tokens),0) FROM ai_summary_attempt WHERE task_id={sql_literal(task_id)}",
            "cancelled attempt budget sum",
        )
        budget_row = self.sql_one(
            f"SELECT charged_tokens FROM ai_summary_budget WHERE user_id={actor.user_id} AND budget_day={sql_literal(utc_now().date().isoformat())}",
            "cancelled budget SQL",
        )
        if (
            task_row[0] != "CANCELLED"
            or task_row[2] != "CANCELLED"
            or task_row[3]
            or attempt_row[0] != "CANCELLED"
            or not attempt_row[1]
            or int(attempt_row[2]) < 0
            or int(attempt_row[3]) < 0
            or int(attempt_row[3]) != int(attempt_row[2])
            or int(budget_row[0]) < int(attempt_sum[0])
            or int(budget_row[0]) != int(attempt_sum[0])
        ):
            raise GateFailure("cancelled task/attempt finished_at or conservative charged budget evidence was inconsistent")
        self.audit_task(task_id, "cancel")

    def content_version_late_result(self) -> None:
        self._setup()
        actor = self.summary_actor("content-version")
        self.reset_fixture("delay")
        thread_id = self.create_summary_thread(actor, "M4B old content", "version")
        response, started = self.start_summary(actor, thread_id, str(__import__("uuid").uuid4()))
        self._status(response, 200, "version start")
        task_id = started.get("taskId")
        if not isinstance(task_id, str):
            raise GateFailure("version task omitted id")
        self.wait_fixture_calls(1)
        response, _ = self._api_request("PATCH", f"/threads/{thread_id}", actor, json={"content": "M4B new content version"})
        self._status(response, 200, "content version update")
        time.sleep(11)
        old_task = self.sql_one(f"SELECT status,summary FROM ai_summary_task WHERE task_id={sql_literal(task_id)}", "old version task SQL")
        current = self.sql_one(f"SELECT content_version,summary_task_id,summary_status,summary FROM threads WHERE id={thread_id}", "current version SQL")
        if old_task[0] != "SUPERSEDED" or old_task[1] or current[1] or current[2] != "IDLE" or current[3] or int(current[0]) <= 1:
            raise GateFailure("late old-version result changed current content summary")
        self.audit_task(task_id, "content-version")

    def restart_durable_and_mq_outage(self) -> None:
        self._setup()
        actor = self.summary_actor("durable")
        self.reset_fixture()
        self.runtime.compose("stop", "rabbitmq", timeout=90)
        thread_id = self.create_summary_thread(actor, "M4B durable pending", "durable")
        response, started = self.start_summary(actor, thread_id, str(__import__("uuid").uuid4()))
        self._status(response, 200, "MQ outage start")
        task_id = started.get("taskId")
        if not isinstance(task_id, str):
            raise GateFailure("durable task omitted id")
        time.sleep(2)
        pending = self.sql_one(f"SELECT status,attempts FROM ai_summary_task WHERE task_id={sql_literal(task_id)}", "durable pending SQL")
        if pending[0] != "PENDING" or int(pending[1]) != 0 or self.fixture_state().get("calls") != 0:
            raise GateFailure("MQ outage did not leave a durable unclaimed PENDING task")
        self.runtime.compose("start", "rabbitmq", timeout=180)
        self.runtime.refresh_ports()
        if "rabbitmq-management" not in self.runtime.ports:
            raise GateFailure("RabbitMQ management port disappeared after restart")
        self.runtime.wait_http(f"http://127.0.0.1:{self.runtime.ports['rabbitmq-management']}/api/health/checks/alarms", {200, 401}, timeout=90)
        result = self.wait_terminal(actor, task_id, timeout=90)
        if result.get("status") != "COMPLETED" or self.fixture_state().get("calls") != 1:
            raise GateFailure("durable PENDING task was not recovered after RabbitMQ restart")
        task_before = self.sql_one(
            f"SELECT status,attempts,content_version,summary,request_id FROM ai_summary_task WHERE task_id={sql_literal(task_id)}",
            "durable task before backend restart",
        )
        attempt_before = self.sql_one(
            f"SELECT COUNT(*),COALESCE(SUM(charged_tokens),0) FROM ai_summary_attempt WHERE task_id={sql_literal(task_id)}",
            "durable attempts before backend restart",
        )
        thread_before = self.sql_one(
            f"SELECT summary_task_id,content_version,summary_status,summary FROM threads WHERE id={thread_id}",
            "durable thread before backend restart",
        )
        request_before = self.sql_one(
            f"SELECT COUNT(*) FROM ai_summary_request WHERE task_id={sql_literal(task_id)}",
            "durable requests before backend restart",
        )
        budget_before = self.sql_one(
            f"SELECT charged_tokens FROM ai_summary_budget WHERE user_id={actor.user_id} AND budget_day={sql_literal(utc_now().date().isoformat())}",
            "durable budget before backend restart",
        )
        backend_port = self.runtime.ports.get("backend")
        frontend_port = self.runtime.ports.get("frontend")
        cors_origin = self.runtime.env_values.get("APP_CORS_ALLOWED_ORIGINS")
        self.runtime.restart_backend_with_admin_email()
        self.refresh_urls()
        if self.runtime.ports.get("backend") != backend_port or self.runtime.ports.get("frontend") != frontend_port:
            raise GateFailure("backend or frontend port changed during durable backend restart")
        if self.runtime.env_values.get("APP_CORS_ALLOWED_ORIGINS") != cors_origin or cors_origin != self.frontend:
            raise GateFailure("CORS origin changed during durable backend restart")
        response, api_summary = self.summary_request(
            "GET", f"/ai/summary/{thread_id}", actor, headers={"Origin": self.frontend}
        )
        self._status(response, 200, "durable summary after backend restart")
        if response.headers.get("access-control-allow-origin") != self.frontend:
            raise GateFailure("summary API did not preserve the exact CORS origin after backend restart")
        task_after = self.sql_one(
            f"SELECT status,attempts,content_version,summary,request_id FROM ai_summary_task WHERE task_id={sql_literal(task_id)}",
            "durable task after backend restart",
        )
        attempt_after = self.sql_one(
            f"SELECT COUNT(*),COALESCE(SUM(charged_tokens),0) FROM ai_summary_attempt WHERE task_id={sql_literal(task_id)}",
            "durable attempts after backend restart",
        )
        thread_after = self.sql_one(
            f"SELECT summary_task_id,content_version,summary_status,summary FROM threads WHERE id={thread_id}",
            "durable thread after backend restart",
        )
        request_after = self.sql_one(
            f"SELECT COUNT(*) FROM ai_summary_request WHERE task_id={sql_literal(task_id)}",
            "durable requests after backend restart",
        )
        budget_after = self.sql_one(
            f"SELECT charged_tokens FROM ai_summary_budget WHERE user_id={actor.user_id} AND budget_day={sql_literal(utc_now().date().isoformat())}",
            "durable budget after backend restart",
        )
        if task_after != task_before or attempt_after != attempt_before or thread_after != thread_before or request_after != request_before or budget_after != budget_before:
            raise GateFailure("durable summary/task/attempt/budget/request readback changed after backend restart")
        if api_summary.get("summary") != thread_after[3] or api_summary.get("status") != "COMPLETED":
            raise GateFailure("durable API summary did not match the persisted post-restart summary")
        self.audit_task(task_id, "durable-restart")

    def _browser_summary(self, viewport: str, theme: str) -> None:
        self._setup()
        assert self.owner is not None
        stop_case = viewport == "landscape" and theme == "day"
        self.reset_fixture("delay" if stop_case else "normal")
        thread_id = self.create_summary_thread(self.owner, f"M4B browser {viewport} {theme}", f"browser-{viewport}-{theme}")
        try:
            from playwright.sync_api import TimeoutError as PlaywrightTimeoutError, sync_playwright
        except ImportError as exc:  # pragma: no cover
            raise GateFailure("Playwright is required for M4B browser acceptance") from exc
        width, height = (390, 844) if viewport == "mobile" else (844, 390)
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True)
            context = browser.new_context(
                viewport={"width": width, "height": height},
                color_scheme="dark" if theme == "night" else "light",
                has_touch=True,
                is_mobile=True,
            )
            page = context.new_page()
            try:
                self._browser_init(page, self.owner, theme)
                page.goto(f"{self.frontend}/threads/{thread_id}", wait_until="domcontentloaded", timeout=int(self.browser_timeout * 1000))
                section = page.locator("section[aria-label='AI 智能摘要']")
                section.wait_for(state="visible", timeout=int(self.browser_timeout * 1000))
                generate = section.get_by_role("button", name="生成 AI 摘要")
                generate.wait_for(state="visible", timeout=int(self.browser_timeout * 1000))
                generate_box = generate.bounding_box()
                if generate_box is None or generate_box["height"] < 44 or generate_box["width"] < 44:
                    raise GateFailure("summary generate control was smaller than the 44px touch target")
                generate.tap()
                if stop_case:
                    stop = section.get_by_role("button", name="停止生成")
                    stop.wait_for(state="visible", timeout=int(self.browser_timeout * 1000))
                    stop_box = stop.bounding_box()
                    if stop_box is None or stop_box["height"] < 44 or stop_box["width"] < 44:
                        raise GateFailure("summary stop control was smaller than the 44px touch target")
                    stop.tap()
                    section.get_by_text("已取消摘要生成。", exact=True).wait_for(
                        state="visible", timeout=int(self.browser_timeout * 1000)
                    )
                    response, summary = self.read_summary(self.owner, thread_id)
                    self._status(response, 200, "browser cancelled summary readback")
                    if summary.get("status") != "CANCELLED" or summary.get("summary"):
                        raise GateFailure("browser stop did not persist a cancelled empty summary")
                else:
                    section.get_by_text(re.compile("controlled summary input-sha256=")).wait_for(
                        state="visible", timeout=int(self.browser_timeout * 1000)
                    )
                    response, summary = self.read_summary(self.owner, thread_id)
                    self._status(response, 200, "browser completed summary readback")
                    if summary.get("status") != "COMPLETED" or not summary.get("summary"):
                        raise GateFailure("browser generation did not persist a completed summary")
                dark = page.locator("html").evaluate("node => node.classList.contains('dark')")
                if dark != (theme == "night"):
                    raise GateFailure("summary browser theme did not match its case")
                if page.evaluate("document.documentElement.scrollWidth <= window.innerWidth") is not True:
                    raise GateFailure("summary browser viewport had horizontal overflow")
                section.scroll_into_view_if_needed()
                page.screenshot(path=str(self.runtime.screenshot_dir / f"summary-{viewport}-{theme}.png"), full_page=False)
                task_id = summary.get("taskId")
                if isinstance(task_id, str):
                    self.audit_task(task_id, f"browser-{viewport}-{theme}")
            except PlaywrightTimeoutError as exc:
                raise GateFailure(f"summary browser action timed out: {safe_text(exc)}") from exc
            finally:
                context.close()
                browser.close()

    def browser_summary_mobile_day(self) -> None:
        self._browser_summary("mobile", "day")

    def browser_summary_landscape_day(self) -> None:
        self._browser_summary("landscape", "day")

    def browser_summary_mobile_night(self) -> None:
        self._browser_summary("mobile", "night")

    def browser_summary_landscape_night(self) -> None:
        self._browser_summary("landscape", "night")

    def write_readback(self) -> None:
        (self.runtime.artifact_dir / "summary-readback.json").write_text(
            json.dumps(self.readback, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )


def write_checks(runtime: ComposeRuntime, reporter: SummaryReporter, started_at: dt.datetime, acceptance: SummaryAcceptance | None) -> None:
    screenshots = sorted(path.name for path in runtime.screenshot_dir.glob("*.png"))
    checks: dict[str, Any] = {
        "project": runtime.project,
        "startedAt": started_at.isoformat(),
        "finishedAt": utc_now().isoformat(),
        "status": "passed" if validate_report(reporter, runtime.cleanup_status) else "failed",
        "executed": bool(reporter.cases),
        "caseCount": len(reporter.cases),
        "expectedCases": len(PLANNED_CASES),
        "skipped": sum(case.status == "skipped" for case in reporter.cases),
        "failures": sum(case.status == "failed" for case in reporter.cases),
        "errors": sum(case.status == "error" for case in reporter.cases),
        "ports": runtime.ports,
        "cleanup": runtime.cleanup_status,
        "externalModelProven": False,
        "controlledFixture": {"image": FIXTURE_IMAGE, "privatePort": FIXTURE_PRIVATE_PORT},
        "screenshots": screenshots,
        "plannedCases": list(PLANNED_CASES),
        "uncovered": [
            "The provider is a local protocol fixture and does not prove an external model call or model quality.",
            "Real-device Safari, OS keyboard behavior and private external credentials remain separate evidence.",
        ],
    }
    if acceptance is not None:
        acceptance.write_readback()
        checks["readback"] = "summary-readback.json"
    runtime.checks_file.write_text(json.dumps(checks, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    reporter.write_junit(runtime.junit_file)


def validate_report(reporter: SummaryReporter, cleanup_status: str) -> bool:
    return [case.name for case in reporter.cases] == list(PLANNED_CASES) and all(case.status == "passed" for case in reporter.cases) and cleanup_status == "passed"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact-dir", default=str(ROOT_DIR / "outputs" / "m4b-runtime-ci"))
    parser.add_argument("--browser-timeout", type=float, default=90.0)
    args = parser.parse_args(argv)
    started_at = utc_now()
    reporter = SummaryReporter()
    artifact_parent = Path(args.artifact_dir).resolve()
    outputs_root = (ROOT_DIR / "outputs").resolve()
    if artifact_parent == outputs_root or not artifact_parent.is_relative_to(outputs_root):
        print("ERROR: M4B artifacts must be stored under workspace outputs", file=sys.stderr)
        return 2
    artifact_parent.mkdir(parents=True, exist_ok=True)
    runtime: ComposeRuntime | None = None
    acceptance: SummaryAcceptance | None = None
    try:
        runtime = ComposeRuntime(artifact_parent)
        if reporter.run("bootstrap", runtime.bootstrap):
            if reporter.run("build", runtime.build) and reporter.run("start", runtime.start):
                acceptance = SummaryAcceptance(runtime, browser_timeout=args.browser_timeout)
                cases: list[tuple[str, Callable[[], None]]] = [
                    ("auth-and-explicit-only", acceptance.auth_and_explicit_only),
                    ("normal-and-usage-readback", acceptance.normal_and_usage_readback),
                    ("long-input-complete", acceptance.long_input_complete),
                    ("idempotency-and-duplicate-delivery", acceptance.idempotency_and_duplicate_delivery),
                    ("scope-revocation-and-hidden", acceptance.scope_revocation_and_hidden),
                    ("budget-exhaustion", acceptance.budget_exhaustion),
                    ("provider-failure-and-retry", acceptance.provider_failure_and_retry),
                    ("timeout-and-recovery", acceptance.timeout_and_recovery),
                    ("cancel-and-late-result", acceptance.cancel_and_late_result),
                    ("content-version-late-result", acceptance.content_version_late_result),
                    ("restart-durable-and-mq-outage", acceptance.restart_durable_and_mq_outage),
                    ("browser-summary-mobile-day", acceptance.browser_summary_mobile_day),
                    ("browser-summary-landscape-day", acceptance.browser_summary_landscape_day),
                    ("browser-summary-mobile-night", acceptance.browser_summary_mobile_night),
                    ("browser-summary-landscape-night", acceptance.browser_summary_landscape_night),
                ]
                for name, action in cases:
                    reporter.run(name, action)
    except Exception as exc:  # pragma: no cover - mandatory artifact safety net
        reporter.cases.append(CaseResult("runner", "error", 0.0, safe_text(f"{type(exc).__name__}: {exc}")))
        if runtime is None:
            raise SystemExit(1) from exc
    finally:
        if runtime is not None:
            runtime.cleanup()
            if runtime.cleanup_status != "passed":
                reporter.cases.append(CaseResult("cleanup", "failed", 0.0, "owned Compose labels or private env cleanup failed"))
            write_checks(runtime, reporter, started_at, acceptance)
            if not validate_report(reporter, runtime.cleanup_status):
                print(f"M4B summary runtime failed: project={runtime.project} artifacts={runtime.artifact_dir}", file=sys.stderr)
                raise SystemExit(1)
            print(f"M4B summary runtime passed: project={runtime.project} artifacts={runtime.artifact_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
