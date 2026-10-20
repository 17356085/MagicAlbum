"""Offline guards for the M4B summary runtime gate.

These tests exercise file generation, evidence boundaries and the controlled
HTTP fixture only.  They never invoke Docker, Compose, Maven or a model.
"""

from __future__ import annotations

import importlib.util
import inspect
import json
import tempfile
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]


def load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ImportError(path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


gate = load("summary_runtime_gate", Path(__file__).with_name("summary-runtime-smoke.py"))
fixture = load("summary_provider_fixture", Path(__file__).with_name("summary-provider-fixture.py"))


class SummaryRuntimeGuards(unittest.TestCase):
    def setUp(self) -> None:
        self.parent = gate.ROOT_DIR / "outputs" / "m4b-gate-unit"
        self.parent.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=self.parent)
        self.root = Path(self.temp.name).resolve()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_plan_is_exactly_eighteen_executed_cases_with_four_browser_cases(self):
        self.assertEqual(len(gate.PLANNED_CASES), 18)
        self.assertEqual(len(gate.PLANNED_API_CASES), 11)
        self.assertEqual(len(gate.PLANNED_BROWSER_CASES), 4)
        self.assertEqual(gate.PLANNED_CASES[:3], ("bootstrap", "build", "start"))
        self.assertFalse(gate.validate_report(gate.SummaryReporter(), "passed"))
        reporter = gate.SummaryReporter()
        reporter.cases = [gate.CaseResult(name, "passed", 0) for name in gate.PLANNED_CASES]
        self.assertTrue(gate.validate_report(reporter, "passed"))
        reporter.cases[0] = gate.CaseResult("bootstrap", "skipped", 0)
        self.assertFalse(gate.validate_report(reporter, "passed"))

    def test_provider_fixture_records_bounded_evidence_and_supports_protocol_modes(self):
        state = fixture.FixtureState()
        body = "secret-body-not-to-record-TAIL-MARKER-1234"
        mode, call_number, record = state.begin_call(body)
        self.assertEqual(mode, "normal")
        self.assertEqual(call_number, 1)
        self.assertEqual(record["inputChars"], len(body))
        state_value = state.snapshot()
        serialized = json.dumps(state_value, ensure_ascii=False)
        self.assertEqual(state_value["calls"], 1)
        self.assertNotIn("secret-body-not-to-record", serialized)
        self.assertIn("TAIL-MARKER-1234", serialized)
        self.assertNotIn("Bearer", serialized)
        self.assertNotIn("Authorization", serialized)
        self.assertEqual(state.set_mode("rate-limit-first")["mode"], "rate-limit-first")
        first_mode, first_call, _ = state.begin_call("another-secret")
        second_mode, second_call, _ = state.begin_call("another-secret")
        self.assertEqual((first_mode, first_call), ("rate-limit-first", 2))
        self.assertEqual((second_mode, second_call), ("rate-limit-first", 3))
        self.assertEqual(state.reset()["calls"], 0)
        handler_source = inspect.getsource(fixture.FixtureHandler)
        self.assertIn("/v1/chat/completions", handler_source)
        self.assertIn("x-request-id", handler_source)

    def test_compose_override_is_loopback_only_read_only_fixture_with_real_adapter_config(self):
        runtime = gate.ComposeRuntime(self.root)
        override = runtime.override_file.read_text(encoding="utf-8")
        env = runtime.env_file.read_text(encoding="utf-8")
        self.assertIn(f"image: {gate.FIXTURE_IMAGE}", override)
        self.assertIn("summary-provider-fixture.py:/fixture/summary-provider-fixture.py:ro", override)
        self.assertIn('"127.0.0.1:0:8088"', override)
        self.assertIn("read_only: true", override)
        self.assertIn("AI_PROVIDER=real", env)
        self.assertIn("AI_API_KEY=test-only", env)
        self.assertIn("AI_BASE_URL=http://summary-fixture:8088/v1", env)
        self.assertIn("AI_MODEL=controlled-summary", env)
        self.assertIn("externalModelProven", inspect.getsource(gate.write_checks))
        runtime.cleanup()
        self.assertFalse(runtime.env_file.exists())

    def test_existing_compose_project_is_never_claimed(self):
        runtime = gate.ComposeRuntime(self.root)
        runtime.docker = lambda *args, **kwargs: "unrelated-existing-container"
        with self.assertRaises(gate.GateFailure):
            runtime.bootstrap()
        self.assertFalse(runtime.compose_touched)
        runtime.cleanup()

    def test_failed_bootstrap_writes_failed_evidence_and_removes_private_env(self):
        real = gate.ComposeRuntime

        class FakeRuntime(real):
            def bootstrap(self):
                raise gate.GateFailure("controlled bootstrap failure")

        with patch.object(gate, "ComposeRuntime", FakeRuntime):
            with self.assertRaises(SystemExit) as error:
                gate.main(["--artifact-dir", str(self.root)])
            self.assertEqual(error.exception.code, 1)
        report_file = next(self.root.glob("*/junit.xml"))
        report = ET.parse(report_file).getroot()
        self.assertEqual(report.attrib["tests"], "1")
        self.assertEqual(report.attrib["failures"], "1")
        self.assertEqual(json.loads(report_file.with_name("checks.json").read_text())["externalModelProven"], False)
        self.assertEqual(list(self.root.glob("*/runtime.env")), [])

    def test_runtime_source_has_no_external_model_or_dotenv_loading(self):
        source = Path(__file__).with_name("summary-runtime-smoke.py").read_text(encoding="utf-8")
        self.assertIn("externalModelProven\": False", source)
        self.assertIn("controlled", source.lower())
        self.assertNotIn("load_dotenv", source)
        self.assertNotIn("dotenv", source.lower())

    def test_redis_acceptance_reuses_production_oauth_binding_lua_and_owned_keys(self):
        source = Path(__file__).with_name("summary-runtime-smoke.py").read_text(encoding="utf-8")
        self.assertIn('"oauth-code-exchange.lua"', source)
        self.assertIn('"__MISSING__"', source)
        self.assertIn('"__EXPIRED__"', source)
        self.assertIn('"__BINDING__"', source)
        self.assertIn('"oauthCodeBindingVerified": True', source)
        self.assertIn('"oauth:code:acceptance-" + secrets.token_hex(16)', source)
        self.assertIn('redis("DEL", key, oauth_key, expired_oauth_key, legacy_oauth_key)', source)

    def test_ci_requires_oauth_binding_evidence_in_the_strict_summary_guard(self):
        source = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertIn("quota.get('oauthCodeBindingVerified') is not True", source)

    def test_auth_scope_browser_and_audit_boundaries_are_explicit(self):
        source = Path(__file__).with_name("summary-runtime-smoke.py").read_text(encoding="utf-8")
        self.assertIn('summary_request("GET", f"/ai/summary/{self.thread_id}", None)', source)
        self.assertIn('self._status(response, 401, "anonymous summary read")', source)
        self.assertIn('self._status(response, 403, "ordinary non-author summary start")', source)
        self.assertIn('self._status(response, 404, "hidden historical summary read")', source)
        self.assertIn("self._browser_init(page, self.owner, theme)", source)
        self.assertIn("has_touch=True", source)
        self.assertIn("is_mobile=True", source)
        self.assertIn("generate.tap()", source)
        self.assertIn("stop.tap()", source)
        self.assertIn("section.scroll_into_view_if_needed()", source)
        self.assertIn("full_page=False", source)
        self.assertIn("finished_at", source)
        self.assertIn('"retried": False', source)
        self.assertIn('"quotaWaits": []', source)
        self.assertIn('"requests": [', source)
        self.assertNotIn('"requestKey"', source)

    def test_duplicate_delivery_uses_discovered_management_url(self):
        seen = {}

        class FakeResponse:
            status_code = 200

            @staticmethod
            def json():
                return {"routed": True}

        class FakeApi:
            def __init__(self, base_url):
                seen["base"] = base_url

            def request(self, method, path, **kwargs):
                seen["request"] = (method, path, kwargs)
                return FakeResponse()

            def close(self):
                seen["closed"] = True

        acceptance = gate.SummaryAcceptance.__new__(gate.SummaryAcceptance)
        acceptance.management = "http://discovered-management:15672"
        acceptance.runtime = type(
            "Runtime", (), {"env_values": {"RABBITMQ_USER": "fixture-user"}, "secrets": {"RABBITMQ_PASSWORD": "fixture-password"}}
        )()
        with patch.object(gate, "BaseHttpApi", FakeApi):
            acceptance.publish_duplicate("task-id")
        self.assertEqual(seen["base"], acceptance.management)
        self.assertEqual(seen["request"][0:2], ("POST", "/api/exchanges/%2F/amq.default/publish"))
        self.assertTrue(seen["closed"])

    def test_registration_retry_waits_for_fixed_window_and_reuses_payload(self):
        class FakeResponse:
            def __init__(self, status_code, body, headers=None):
                self.status_code = status_code
                self._body = body
                self.headers = headers or {}

            def json(self):
                return self._body

        acceptance = gate.SummaryAcceptance.__new__(gate.SummaryAcceptance)
        acceptance.readback = {}
        calls = []
        responses = [
            FakeResponse(429, {"error": "rate limited"}),
            FakeResponse(201, {"id": 123}),
        ]

        def fake_api(method, path, **kwargs):
            calls.append((method, path, kwargs["json"]))
            response = responses.pop(0)
            return response, response._body

        acceptance._api_request = fake_api
        acceptance.login_identity = lambda identity, label: identity
        with patch.object(gate.time, "time", return_value=1000.0), patch.object(
            gate.time, "monotonic", side_effect=[10.0, 212.0]
        ), patch.object(gate.time, "sleep") as sleep:
            result = acceptance.register_login("offline")

        self.assertEqual(result.user_id, 123)
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[0], calls[1])
        chunks = [call.args[0] for call in sleep.call_args_list]
        self.assertTrue(chunks)
        self.assertLessEqual(max(chunks), gate.REGISTRATION_SLEEP_CHUNK_SECONDS)
        self.assertAlmostEqual(sum(chunks), 202.0)
        self.assertEqual(acceptance.readback["registrationWaits"][0]["retryAfterSeconds"], 202.0)
        self.assertEqual(acceptance.readback["registrationWaits"][0]["waitSeconds"], 202.0)

        header_response = FakeResponse(429, {}, {"Retry-After": "7"})
        self.assertEqual(gate.SummaryAcceptance._registration_retry_seconds(header_response, now=1000.0), 7.0)

    def test_registration_retry_surfaces_second_fixed_window_429_without_looping(self):
        class FakeResponse:
            status_code = 429
            headers = {"Retry-After": "0"}

            @staticmethod
            def json():
                return {"error": "rate limited"}

        acceptance = gate.SummaryAcceptance.__new__(gate.SummaryAcceptance)
        acceptance.readback = {}
        calls = []

        def fake_api(method, path, **kwargs):
            calls.append(kwargs["json"])
            return FakeResponse(), {"error": "rate limited"}

        acceptance._api_request = fake_api
        with patch.object(gate.time, "monotonic", return_value=10.0), patch.object(gate.time, "sleep"):
            with self.assertRaisesRegex(gate.GateFailure, "after one retry"):
                acceptance.register_login("offline-429")
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[0], calls[1])


if __name__ == "__main__":
    unittest.main()
