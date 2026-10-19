#!/usr/bin/env python3
"""M4A community governance runtime acceptance gate.

The gate is deliberately a thin extension of the already verified M3 media
runtime gate.  It imports its Compose, HTTP, reporting, logging and cleanup
implementations instead of maintaining a second destructive runner.  A CI
run starts an isolated nine-service Compose project and drives the Spring
HTTP APIs against the real MySQL database.  The local guard tests exercise
only the bootstrap, reporting and cleanup boundaries; they never start
Docker.
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import importlib.util
import json
import os
import re
import secrets
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any, Callable, Iterable


ROOT_DIR = Path(__file__).resolve().parents[2]
M3_SCRIPT = Path(__file__).with_name("media-runtime-smoke.py")
_M3_SPEC = importlib.util.spec_from_file_location("bluealbum_media_runtime", M3_SCRIPT)
if _M3_SPEC is None or _M3_SPEC.loader is None:  # pragma: no cover - import guard
    raise ImportError(f"cannot load verified runtime gate: {M3_SCRIPT}")
_M3 = importlib.util.module_from_spec(_M3_SPEC)
sys.modules[_M3_SPEC.name] = _M3
_M3_SPEC.loader.exec_module(_M3)

BaseAcceptance = _M3.Acceptance
BaseComposeRuntime = _M3.ComposeRuntime
BaseHttpApi = _M3.HttpApi
BaseReporter = _M3.Reporter
CaseResult = _M3.CaseResult
GateFailure = _M3.GateFailure
Identity = _M3.Identity
safe_text = _M3.safe_text

API_ROOT = "/api/v1"
PLANNED_API_CASES = (
    "bootstrap-identities-and-admin-seeding",
    "governance-shape-and-authorization",
    "section-management-and-readback",
    "moderator-scope-and-cross-section-denial",
    "thread-post-inheritance-and-write-guards",
    "posting-restriction-and-archived-section",
    "report-hide-restore-detail-list",
    "report-parent-and-author-deletion-guards",
    "report-search-and-db-readback",
    "audit-idempotency-and-sql-readback",
    "last-admin-revocation-and-restart",
    "post-revocation-write-readback",
)
PLANNED_BROWSER_CASES = tuple(
    f"browser-{page}-{viewport}-{theme}"
    for page in ("community", "reports")
    for viewport in ("mobile", "desktop")
    for theme in ("day", "night")
)
PLANNED_CASES = ("bootstrap", "build", "start", *PLANNED_API_CASES, *PLANNED_BROWSER_CASES)


def utc_now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


def sql_literal(value: object) -> str:
    """Quote a value used in a read-only query built from generated data."""

    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, int):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"


class M4Reporter(BaseReporter):
    """M4 JUnit reporter with an explicit zero-skip contract."""

    def write_junit(self, path: Path) -> None:
        failed = sum(case.status == "failed" for case in self.cases)
        errors = sum(case.status == "error" for case in self.cases)
        skipped = sum(case.status == "skipped" for case in self.cases)
        suite = ET.Element(
            "testsuite",
            {
                "name": "bluealbum-m4-community-runtime",
                "tests": str(len(self.cases)),
                "failures": str(failed),
                "errors": str(errors),
                "skipped": str(skipped),
            },
        )
        for case in self.cases:
            node = ET.SubElement(
                suite,
                "testcase",
                {"name": case.name, "time": f"{case.duration_seconds:.3f}"},
            )
            if case.status == "failed":
                ET.SubElement(node, "failure", {"message": case.detail or "acceptance failure"}).text = case.detail
            elif case.status == "error":
                ET.SubElement(node, "error", {"message": case.detail or "unexpected error"}).text = case.detail
            elif case.status == "skipped":
                ET.SubElement(node, "skipped")
        path.write_bytes(ET.tostring(suite, encoding="utf-8", xml_declaration=True))


class ComposeRuntime(BaseComposeRuntime):
    """Reuse M3 Compose lifecycle while changing only the M4 identity/config."""

    def __init__(self, artifact_parent: Path) -> None:
        # BaseComposeRuntime calls the overridable writers.  During that call
        # its temporary project has an M3 prefix; it is renamed immediately
        # after creation before any Compose command can be issued.
        super().__init__(artifact_parent)
        old_project = self.project
        suffix = old_project.removeprefix("bluealbum-m3-")
        new_project = f"bluealbum-m4-{suffix}"
        old_artifact = self.artifact_dir
        new_artifact = artifact_parent / new_project
        if new_artifact.exists():
            raise GateFailure("generated M4 artifact directory already exists")
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
        # Keep the M3 security/bootstrap defaults and private credentials, but
        # use separate database/user names so a local M3 run cannot collide.
        values = {
            "DB_NAME": "bluealbum_m4",
            "NOTIFICATION_DB_NAME": "bluealbum_m4_notifications",
            "DB_USER": "bluealbum_m4",
            "RABBITMQ_USER": "bluealbum_m4",
            "JWT_ISSUER": "MagicAlbum",
            "APP_ADMIN_EMAIL": self.env_values.get("APP_ADMIN_EMAIL", ""),
            "APP_AUTH_VERIFY_MODE": "off",
            "APP_RABBIT_ENABLED": "true",
            "APP_CORS_ALLOWED_ORIGINS": self.env_values.get("APP_CORS_ALLOWED_ORIGINS", "http://127.0.0.1:5173"),
            "MEDIA_PROCESSING_ENABLED": "true",
            "MEDIA_REGISTRATION_ENABLED": "true",
            "MEDIA_INTERNAL_SERVICE_TOKEN": self.secrets["MEDIA_INTERNAL_SERVICE_TOKEN"],
            "S3_BUCKET": "media-test",
            "S3_REGION": "us-east-1",
            "S3_ENDPOINT": "http://s3mock:9090",
            "S3_PUBLIC_BASE_URL": public_base_url,
            "S3_ACCESS_KEY": self.secrets["S3_ACCESS_KEY"],
            "S3_SECRET_KEY": self.secrets["S3_SECRET_KEY"],
            "MEDIA_AMQP_URL": f"amqp://bluealbum_m4:{self.secrets['RABBITMQ_PASSWORD']}@rabbitmq:5672/",
        }
        values.update(self.secrets)
        self.env_values = values
        self.env_file.write_text(
            "\n".join(f"{key}={value}" for key, value in values.items()) + "\n",
            encoding="utf-8",
            newline="\n",
        )
        try:
            self.env_file.chmod(0o600)
        except OSError:
            pass

    def _write_override(self) -> None:
        # The verified M3 resource limits, ports and service hardening remain
        # authoritative.  Only the explicitly named volume is M4-scoped.
        super()._write_override()
        text = self.override_file.read_text(encoding="utf-8")
        self.override_file.write_text(text.replace("m3s3mockdata", "m4s3mockdata"), encoding="utf-8", newline="\n")

    def set_admin_email(self, email: str) -> None:
        email = email.strip()
        if not email or "\n" in email or "\r" in email:
            raise GateFailure("APP_ADMIN_EMAIL must be a non-empty single-line email")
        self.env_values["APP_ADMIN_EMAIL"] = email
        self._write_env(self.env_values["S3_PUBLIC_BASE_URL"])

    def restart_backend_with_admin_email(self) -> None:
        before = dict(self.ports)
        # APP_ADMIN_EMAIL is the only changed private env value.  The public
        # S3 URL, CORS origin and frontend port are checked after recreation.
        self.compose("up", "--no-deps", "-d", "--wait", "--wait-timeout", "180", "backend", timeout=300)
        self.refresh_ports()
        for key in ("frontend", "s3mock"):
            if key in before and self.ports.get(key) != before[key]:
                raise GateFailure(f"{key} port changed while applying APP_ADMIN_EMAIL")
        if "backend" not in self.ports:
            raise GateFailure("backend port disappeared while applying APP_ADMIN_EMAIL")
        self.wait_http(f"http://127.0.0.1:{self.ports['backend']}/actuator/health", {200}, timeout=180)

    def cleanup(self) -> None:
        try:
            super().cleanup()
        finally:
            # Keep the private env deletion invariant even if a future base
            # cleanup change raises before reaching its own finally block.
            try:
                if self.env_file.exists():
                    self.env_file.unlink()
            except OSError:
                self.cleanup_status = "failed"


class CommunityAcceptance(BaseAcceptance):
    """Real HTTP/SQL/API and browser acceptance for M4 governance."""

    def __init__(self, runtime: ComposeRuntime, browser_timeout: float = 90.0) -> None:
        super().__init__(runtime, browser_timeout=browser_timeout)
        self.runtime: ComposeRuntime = runtime
        self.identities: dict[str, Identity] = {}
        self.primary_admin: Identity | None = None
        self.admin: Identity | None = None
        self.sections: dict[str, dict[str, Any]] = {}
        self.threads: dict[str, int] = {}
        self.posts: dict[str, int] = {}
        self.reports: dict[str, int] = {}
        self.sql_columns: dict[str, set[str]] = {}
        self.readback: dict[str, Any] = {"search": {"esProven": False}}
        self.browser_timeout = browser_timeout

    @property
    def planned_browser_cases(self) -> tuple[str, ...]:
        return PLANNED_BROWSER_CASES

    def api_path(self, path: str) -> str:
        if path.startswith("/api/"):
            return path
        return f"{API_ROOT}{path if path.startswith('/') else '/' + path}"

    def _api_request(
        self,
        method: str,
        path: str,
        identity: Identity | None = None,
        **kwargs: Any,
    ) -> tuple[Any, Any]:
        api = BaseHttpApi(self.gateway)
        try:
            headers = dict(kwargs.pop("headers", {}) or {})
            if identity is not None:
                headers = {**self.auth_headers(identity), **headers}
            response = api.request(method, self.api_path(path), headers=headers, **kwargs)
            try:
                body = response.json()
            except Exception:
                body = None
            return response, body
        finally:
            api.close()

    def _status(self, response: Any, expected: int | Iterable[int], label: str) -> None:
        expected_set = {expected} if isinstance(expected, int) else set(expected)
        if response.status_code not in expected_set:
            raise GateFailure(f"{label} returned status {response.status_code}, expected {sorted(expected_set)}")

    def _dict(self, body: Any, label: str) -> dict[str, Any]:
        if not isinstance(body, dict):
            raise GateFailure(f"{label} returned a non-object JSON body")
        return body

    def _list(self, body: Any, label: str) -> list[Any]:
        if not isinstance(body, list):
            raise GateFailure(f"{label} returned a non-array JSON body")
        return body

    def refresh_urls(self) -> None:
        super().refresh_urls()
        if "gateway" not in self.runtime.ports or "frontend" not in self.runtime.ports:
            raise GateFailure("M4 runtime did not expose gateway and frontend ports")

    def register_login(self, label: str) -> Identity:
        stamp = secrets.token_hex(5)
        username = f"m4_{label}_{stamp}"
        email = f"{username}@example.test"
        phone = "1" + str(secrets.randbelow(10**10)).zfill(10)
        password = "M4-runtime-" + secrets.token_urlsafe(18)
        response, body = self._api_request(
            "POST",
            "/users/register",
            json={"username": username, "email": email, "phone": phone, "password": password},
        )
        self._status(response, 201, f"{label} registration")
        created = self._dict(body, f"{label} registration")
        user_id = created.get("id")
        if not isinstance(user_id, int):
            raise GateFailure(f"{label} registration did not return an integer id")
        return self.login_identity(Identity(username, email, phone, password, user_id, "", created), label)

    def login_identity(self, identity: Identity, label: str | None = None) -> Identity:
        label = label or identity.username
        response, body = self._api_request(
            "POST", "/auth/login", json={"email": identity.email, "password": identity.password}
        )
        self._status(response, 200, f"{label} login")
        value = self._dict(body, f"{label} login")
        token = value.get("accessToken")
        user = value.get("user")
        if not isinstance(token, str) or not token or not isinstance(user, dict):
            raise GateFailure(f"{label} login omitted accessToken/user")
        if user.get("id") != identity.user_id:
            raise GateFailure(f"{label} login identity changed")
        return dataclasses.replace(identity, token=token, user=user)

    def governance(self, identity: Identity) -> dict[str, Any]:
        response, body = self._api_request("GET", "/governance/me", identity)
        self._status(response, 200, "governance/me")
        access = self._dict(body, "governance/me")
        for key in ("admin", "postingRestricted"):
            if not isinstance(access.get(key), bool):
                raise GateFailure(f"governance/me {key} was not boolean")
        if not isinstance(access.get("moderatedSectionIds"), list):
            raise GateFailure("governance/me moderatedSectionIds was not an array")
        if access.get("restrictedUntil") is not None and not isinstance(access.get("restrictedUntil"), str):
            raise GateFailure("governance/me restrictedUntil was neither null nor a string")
        return access

    def list_users(self, identity: Identity, q: str = "", page: int = 1, size: int = 50) -> dict[str, Any]:
        response, body = self._api_request(
            "GET", "/governance/users", identity, params={"q": q, "page": page, "size": size}
        )
        self._status(response, 200, "users list")
        result = self._dict(body, "users list")
        if not isinstance(result.get("items"), list):
            raise GateFailure("users list omitted items array")
        if result.get("page") != page or result.get("size") != size or not isinstance(result.get("total"), int):
            raise GateFailure("users list page metadata did not match the requested page")
        for item in result["items"]:
            value = self._dict(item, "users list item")
            for key in ("id", "username", "admin", "moderatedSectionIds", "postingRestricted", "restrictedUntil"):
                if key not in value:
                    raise GateFailure(f"users list item omitted {key}")
            if not isinstance(value["id"], int) or not isinstance(value["username"], str):
                raise GateFailure("users list item identity types were invalid")
            if not isinstance(value["admin"], bool) or not isinstance(value["postingRestricted"], bool):
                raise GateFailure("users list item flags were invalid")
            if not isinstance(value["moderatedSectionIds"], list):
                raise GateFailure("users list item moderatedSectionIds was not an array")
        return result

    def manage_user(self, identity: Identity, target_id: int, path: str, payload: dict[str, Any], label: str) -> Any:
        response, body = self._api_request("PUT", f"/governance/users/{target_id}/{path}", identity, json=payload)
        self._status(response, 200, label)
        return body

    def get_sections(self, identity: Identity) -> list[dict[str, Any]]:
        response, body = self._api_request("GET", "/governance/sections", identity)
        self._status(response, 200, "sections list")
        rows = self._list(body, "sections list")
        required = ("id", "name", "slug", "description", "rules", "visible", "archived")
        for row in rows:
            value = self._dict(row, "sections list item")
            for key in required:
                if key not in value:
                    raise GateFailure(f"sections list item omitted {key}")
            if not isinstance(value["id"], int) or not isinstance(value["visible"], bool) or not isinstance(value["archived"], bool):
                raise GateFailure("sections list item types were invalid")
        return rows

    def create_section(self, identity: Identity, label: str, archived: bool = False) -> dict[str, Any]:
        stamp = secrets.token_hex(4)
        name = f"M4 {label} {stamp}"
        payload = {
            "name": name,
            "slug": f"m4-{label}-{stamp}",
            "description": f"M4 runtime {label} {stamp}",
            "rules": "M4 runtime acceptance section",
            "visible": True,
            "archived": archived,
            "reason": f"M4 create {label}",
        }
        response, body = self._api_request("POST", "/governance/sections", identity, json=payload)
        self._status(response, {200, 201}, f"create section {label}")
        value = self._dict(body, f"create section {label}")
        if not isinstance(value.get("id"), int):
            raise GateFailure(f"create section {label} did not return an id")
        return value

    def update_section(self, identity: Identity, section_id: int, patch: dict[str, Any], label: str) -> dict[str, Any]:
        response, body = self._api_request("PUT", f"/governance/sections/{section_id}", identity, json=patch)
        self._status(response, 200, label)
        return self._dict(body, label)

    def create_thread(self, identity: Identity, section_id: int, marker: str) -> dict[str, Any]:
        response, body = self._api_request(
            "POST",
            "/threads",
            identity,
            json={"sectionId": section_id, "title": f"M4 {marker}", "content": f"M4 runtime {marker}", "tags": ["m4"]},
        )
        self._status(response, 201, f"create thread {marker}")
        value = self._dict(body, f"create thread {marker}")
        if not isinstance(value.get("id"), int):
            raise GateFailure(f"create thread {marker} did not return id")
        return value

    def get_thread(self, identity: Identity, thread_id: int) -> dict[str, Any]:
        response, body = self._api_request("GET", f"/threads/{thread_id}", identity)
        self._status(response, 200, f"read thread {thread_id}")
        return self._dict(body, "thread read")

    def update_thread(self, identity: Identity, thread_id: int, **patch: Any) -> Any:
        response, body = self._api_request("PATCH", f"/threads/{thread_id}", identity, json=patch)
        return response, body

    def delete_thread(self, identity: Identity, thread_id: int) -> Any:
        response, body = self._api_request("DELETE", f"/threads/{thread_id}", identity)
        return response, body

    def create_post(self, identity: Identity, thread_id: int, marker: str, reply_to: int | None = None) -> dict[str, Any]:
        payload: dict[str, Any] = {"contentMd": f"M4 runtime comment {marker}"}
        if reply_to is not None:
            payload["replyToPostId"] = reply_to
        response, body = self._api_request("POST", f"/threads/{thread_id}/posts", identity, json=payload)
        self._status(response, 201, f"create post {marker}")
        value = self._dict(body, f"create post {marker}")
        if not isinstance(value.get("id"), int):
            raise GateFailure(f"create post {marker} did not return id")
        return value

    def update_post(self, identity: Identity, post_id: int, marker: str) -> Any:
        return self._api_request("PATCH", f"/posts/{post_id}", identity, json={"contentMd": f"M4 edited {marker}"})

    def create_report(self, identity: Identity, target_type: str, target_id: int, marker: str) -> dict[str, Any]:
        response, body = self._api_request(
            "POST",
            "/reports",
            identity,
            json={"targetType": target_type, "targetId": target_id, "reason": "SPAM", "detail": f"M4 report {marker}"},
        )
        self._status(response, 201, f"create report {marker}")
        value = self._dict(body, f"create report {marker}")
        if not isinstance(value.get("id"), int):
            raise GateFailure(f"create report {marker} did not return id")
        return value

    def list_reports(self, identity: Identity, status: str) -> dict[str, Any]:
        response, body = self._api_request(
            "GET", "/admin/reports", identity, params={"status": status, "page": 1, "size": 50}
        )
        self._status(response, 200, f"reports {status}")
        value = self._dict(body, f"reports {status}")
        if not isinstance(value.get("items"), list) or value.get("page") != 1 or value.get("size") != 50 or not isinstance(value.get("total"), int):
            raise GateFailure(f"reports {status} page shape was invalid")
        for item in value["items"]:
            report = self._dict(item, "report item")
            for key in ("id", "reporterId", "targetType", "targetId", "reason", "status", "createdAt"):
                if key not in report:
                    raise GateFailure(f"report item omitted {key}")
        return value

    def resolve_report(self, identity: Identity, report_id: int, action: str, reason: str) -> tuple[Any, Any]:
        return self._api_request(
            "POST",
            f"/admin/reports/{report_id}/resolve",
            identity,
            json={"action": action, "reason": reason},
        )

    def discover_sql_schema(self) -> dict[str, set[str]]:
        tables = (
            "community_admin_role",
            "community_moderator_scope",
            "community_posting_restriction",
            "community_audit",
            "community_state",
            "threads",
            "posts",
            "content_reports",
        )
        rows = self.runtime.sql(
            "SELECT table_name FROM information_schema.tables "
            "WHERE table_schema=DATABASE() AND table_name IN (" + ",".join(sql_literal(t) for t in tables) + ")"
        )
        present = {row[0] for row in rows if row}
        missing = set(tables[:5]) - present
        if missing:
            raise GateFailure(f"required M4 tables were missing: {sorted(missing)}")
        result: dict[str, set[str]] = {}
        for table in present:
            columns = self.runtime.sql(
                "SELECT column_name FROM information_schema.columns "
                f"WHERE table_schema=DATABASE() AND table_name={sql_literal(table)}"
            )
            result[table] = {row[0] for row in columns if row}
        required = {
            "community_admin_role": {"user_id"},
            "community_moderator_scope": {"user_id", "section_id"},
            "community_posting_restriction": {"user_id", "restricted_until"},
            "community_audit": {"actor_id", "action", "target_type", "target_id", "reason", "occurred_at", "request_id"},
            "community_state": {"id", "bootstrapped"},
        }
        for table, needed in required.items():
            if not needed.issubset(result.get(table, set())):
                raise GateFailure(f"{table} did not expose expected columns: {sorted(needed - result.get(table, set()))}")
        self.sql_columns = result
        return result

    def sql_scalar(self, query: str, label: str) -> str:
        rows = self.runtime.sql(query)
        if len(rows) != 1 or len(rows[0]) != 1:
            raise GateFailure(f"{label} returned an unexpected SQL shape")
        return rows[0][0]

    def sql_count(self, query: str, label: str) -> int:
        value = self.sql_scalar(query, label)
        try:
            return int(value)
        except ValueError as exc:
            raise GateFailure(f"{label} returned a non-integer count") from exc

    def sql_report_audit_count(self, report_id: int, action: str | None = None) -> int:
        target_type: str | None = None
        target_id: int | None = None
        for key, value in self.reports.items():
            if value != report_id:
                continue
            if key.startswith("thread_"):
                target_type = "THREAD"
                target_id = self.threads.get(key.removeprefix("thread_"))
            elif key.startswith("post_"):
                target_type = "POST"
                target_id = self.posts.get(key.removeprefix("post_"))
            break
        if target_type is None or target_id is None:
            raise GateFailure(f"cannot map report {report_id} to its content target for SQL audit readback")
        clauses = [f"target_id={target_id}", f"target_type={sql_literal(target_type)}"]
        if action is not None:
            clauses.append(f"action={sql_literal(action)}")
        return self.sql_count(
            "SELECT COUNT(*) FROM community_audit WHERE " + " AND ".join(clauses),
            "community audit count",
        )

    def bootstrap_identities_and_admin_seeding(self) -> None:
        self.refresh_urls()
        self.identities["admin"] = self.register_login("admin")
        self.identities["secondadmin"] = self.register_login("secondadmin")
        self.identities["moderator"] = self.register_login("moderator")
        self.identities["ordinary"] = self.register_login("ordinary")
        self.identities["other"] = self.register_login("other")
        self.primary_admin = self.identities["admin"]
        self.runtime.set_admin_email(self.primary_admin.email)
        previous_ports = dict(self.runtime.ports)
        self.runtime.restart_backend_with_admin_email()
        self.refresh_urls()
        if self.runtime.env_values.get("APP_ADMIN_EMAIL") != self.primary_admin.email:
            raise GateFailure("private APP_ADMIN_EMAIL did not retain the selected admin")
        for key in ("admin", "secondadmin", "moderator", "ordinary", "other"):
            self.identities[key] = self.login_identity(self.identities[key], key)
        self.admin = self.identities["admin"]
        access = self.governance(self.admin)
        if access["admin"] is not True:
            raise GateFailure("configured APP_ADMIN_EMAIL did not seed exactly the initial admin")
        if self.governance(self.identities["ordinary"])["admin"]:
            raise GateFailure("ordinary account was unexpectedly made admin")
        self.discover_sql_schema()
        self.readback["ports"] = {key: self.runtime.ports.get(key) for key in sorted(self.runtime.ports)}
        self.readback["initialAdminId"] = self.primary_admin.user_id
        self.readback["initialPortsStable"] = all(self.runtime.ports.get(k) == previous_ports.get(k) for k in ("frontend", "s3mock"))

    def governance_shape_and_authorization(self) -> None:
        admin = self.admin
        ordinary = self.identities.get("ordinary")
        if admin is None or ordinary is None:
            raise GateFailure("bootstrap identities were not initialized")
        access = self.governance(ordinary)
        if access["admin"]:
            raise GateFailure("ordinary governance access claimed admin")
        for path, label in (("/governance/users", "ordinary users list"), ("/governance/sections", "ordinary sections list"), ("/governance/audit", "ordinary audit list"), ("/admin/reports", "ordinary reports list")):
            response, _ = self._api_request("GET", path, ordinary, params={"page": 1, "size": 20})
            self._status(response, {401, 403}, label)
        page = self.list_users(admin)
        ids = {item["id"] for item in page["items"]}
        if ordinary.user_id not in ids or self.primary_admin is None or self.primary_admin.user_id not in ids:
            raise GateFailure("admin user listing did not contain the registered identities")

    def section_management_and_readback(self) -> None:
        if self.admin is None:
            raise GateFailure("admin identity was not initialized")
        section_a = self.create_section(self.admin, "alpha")
        section_b = self.create_section(self.admin, "beta")
        section_archived = self.create_section(self.admin, "archive")
        self.sections = {"a": section_a, "b": section_b, "archived": section_archived}
        updated = self.update_section(
            self.admin,
            int(section_b["id"]),
            {**{key: section_b[key] for key in ("name", "slug", "description", "rules", "visible", "archived")}, "description": "M4 edited beta", "reason": "M4 section edit"},
            "edit beta section",
        )
        if updated.get("description") != "M4 edited beta":
            raise GateFailure("section edit did not return updated description")
        rows = self.get_sections(self.admin)
        by_id = {int(row["id"]): row for row in rows}
        for key, section in self.sections.items():
            if int(section["id"]) not in by_id:
                raise GateFailure(f"created section {key} was missing from admin list")
        self.readback["sections"] = [by_id[int(section["id"])] for section in self.sections.values()]

    def moderator_scope_and_cross_section_denial(self) -> None:
        admin = self.admin
        moderator = self.identities.get("moderator")
        if admin is None or moderator is None or not self.sections:
            raise GateFailure("moderator scope prerequisites were not initialized")
        section_a = int(self.sections["a"]["id"])
        section_b = int(self.sections["b"]["id"])
        self.manage_user(
            admin,
            moderator.user_id,
            "moderator-sections",
            {"sectionIds": [section_a], "reason": "M4 grant alpha scope"},
            "grant moderator alpha scope",
        )
        moderator = self.login_identity(moderator, "moderator after grant")
        self.identities["moderator"] = moderator
        access = self.governance(moderator)
        if section_a not in [int(value) for value in access["moderatedSectionIds"]]:
            raise GateFailure("moderator governance did not expose granted section")
        response, _ = self._api_request("GET", "/governance/sections", self.identities["moderator"])
        self._status(response, 403, "moderator section administration")
        self.readback["moderatorScopeInitial"] = access["moderatedSectionIds"]
        # The beta scope is granted and revoked later around a real report.  A
        # moderator cannot resolve a report outside the current scope.
        self.readback["crossSectionScope"] = {"alpha": section_a, "beta": section_b}

    def thread_post_inheritance_and_write_guards(self) -> None:
        ordinary = self.identities.get("ordinary")
        if ordinary is None or not self.sections:
            raise GateFailure("content prerequisites were not initialized")
        thread_a = self.create_thread(ordinary, int(self.sections["a"]["id"]), "alpha-thread")
        thread_b = self.create_thread(ordinary, int(self.sections["b"]["id"]), "beta-thread")
        thread_archived = self.create_thread(ordinary, int(self.sections["archived"]["id"]), "archived-thread")
        self.threads = {key: int(value["id"]) for key, value in {"a": thread_a, "b": thread_b, "archived": thread_archived}.items()}
        post_a = self.create_post(ordinary, self.threads["a"], "alpha-post")
        self.posts["a"] = int(post_a["id"])
        post_b = self.create_post(ordinary, self.threads["b"], "beta-post")
        self.posts["b"] = int(post_b["id"])
        row = self.get_thread(ordinary, self.threads["a"])
        if int(row.get("sectionId", -1)) != int(self.sections["a"]["id"]):
            raise GateFailure("thread did not retain its section identity")
        self.readback["content"] = {"threads": self.threads, "posts": self.posts}

    def posting_restriction_and_archived_section(self) -> None:
        admin = self.admin
        ordinary = self.identities.get("ordinary")
        if admin is None or ordinary is None or "a" not in self.threads:
            raise GateFailure("posting guard prerequisites were not initialized")
        until = (utc_now() + dt.timedelta(hours=1)).isoformat().replace("+00:00", "Z")
        self.manage_user(
            admin,
            ordinary.user_id,
            "posting-restriction",
            {"restricted": True, "until": until, "reason": "M4 posting restriction"},
            "restrict ordinary posting",
        )
        restricted = self.governance(self.login_identity(ordinary, "ordinary restricted"))
        if not restricted["postingRestricted"]:
            raise GateFailure("posting restriction was not visible in governance/me")
        response, _ = self.update_thread(ordinary, self.threads["a"], content="M4 restricted edit")
        self._status(response, 403, "restricted thread edit")
        response, _ = self._api_request("POST", "/threads", ordinary, json={"sectionId": int(self.sections["a"]["id"]), "title": "M4 blocked", "content": "M4 blocked"})
        self._status(response, 403, "restricted thread create")
        response, _ = self._api_request("POST", f"/threads/{self.threads['a']}/posts", ordinary, json={"contentMd": "M4 blocked post"})
        self._status(response, 403, "restricted post create")
        self._status(self._api_request("GET", f"/threads/{self.threads['a']}", ordinary)[0], 200, "restricted read")
        self.manage_user(
            admin,
            ordinary.user_id,
            "posting-restriction",
            {"restricted": False, "until": None, "reason": "M4 lift posting restriction"},
            "lift ordinary posting restriction",
        )
        lifted = self.governance(self.login_identity(ordinary, "ordinary unrestricted"))
        if lifted["postingRestricted"]:
            raise GateFailure("posting restriction remained after the lift")
        response, _ = self.update_thread(ordinary, self.threads["a"], content="M4 writable after lift")
        self._status(response, 200, "thread edit after restriction lift")
        archive_id = int(self.sections["archived"]["id"])
        updated_archive = self.update_section(
            admin,
            archive_id,
            {**{key: self.sections["archived"][key] for key in ("name", "slug", "description", "rules", "visible")}, "archived": True, "reason": "M4 archive section"},
            "archive section",
        )
        if updated_archive.get("archived") is not True:
            raise GateFailure("section archive did not persist")
        self._status(self._api_request("GET", f"/threads/{self.threads['archived']}", ordinary)[0], 200, "archived thread read")
        response, _ = self._api_request("POST", "/threads", ordinary, json={"sectionId": archive_id, "title": "M4 archived blocked", "content": "M4 archived blocked"})
        self._status(response, 403, "archived thread create")
        response, _ = self.update_thread(ordinary, self.threads["a"], sectionId=archive_id)
        self._status(response, 403, "move thread into archived section")

    def _report_item(self, page: dict[str, Any], report_id: int, status: str) -> dict[str, Any]:
        for item in page["items"]:
            if item.get("id") == report_id:
                if item.get("status") != status:
                    raise GateFailure(f"report {report_id} list item had status {item.get('status')}, expected {status}")
                return item
        raise GateFailure(f"report {report_id} was missing from {status} list")

    def report_hide_restore_detail_list(self) -> None:
        admin = self.admin
        moderator = self.identities.get("moderator")
        other = self.identities.get("other")
        if admin is None or moderator is None or other is None or not self.threads:
            raise GateFailure("report prerequisites were not initialized")
        report_a = self.create_report(other, "THREAD", self.threads["a"], "alpha-thread")
        report_post = self.create_report(other, "POST", self.posts["a"], "alpha-post")
        report_b = self.create_report(other, "THREAD", self.threads["b"], "beta-thread")
        self.reports = {"thread_a": int(report_a["id"]), "post_a": int(report_post["id"]), "thread_b": int(report_b["id"])}
        pending = self.list_reports(admin, "PENDING")
        item = self._report_item(pending, self.reports["thread_a"], "PENDING")
        if item.get("detail") != "M4 report alpha-thread":
            raise GateFailure("report detail was not preserved")
        mod_page = self.list_reports(moderator, "PENDING")
        ids = {int(row["id"]) for row in mod_page["items"]}
        if self.reports["thread_a"] not in ids or self.reports["thread_b"] in ids:
            raise GateFailure("moderator report list was not filtered to its section scope")
        response, _ = self.resolve_report(moderator, self.reports["thread_a"], "HIDE", "M4 moderator hide alpha")
        self._status(response, 200, "moderator hide own-section report")
        for path in (f"/threads/{self.threads['a']}", f"/threads/{self.threads['a']}/posts", f"/ai/summary/{self.threads['a']}"):
            self._status(self._api_request("GET", path, other)[0], 404, "hidden content read guard")
        response, body = self._api_request("GET", "/threads", other, params={"q": "M4 writable after lift"})
        self._status(response, 200, "hidden content list guard")
        if any(item.get("id") == self.threads["a"] for item in self._dict(body, "hidden thread list").get("items", [])):
            raise GateFailure("hidden thread leaked into the current-content list")
        hidden = self.list_reports(admin, "HIDDEN")
        self._report_item(hidden, self.reports["thread_a"], "HIDDEN")
        response, _ = self.resolve_report(moderator, self.reports["thread_a"], "RESTORE", "M4 moderator restore alpha")
        self._status(response, 200, "moderator restore own-section report")
        self.get_thread(other, self.threads["a"])
        restored = self.list_reports(admin, "RESTORED")
        self._report_item(restored, self.reports["thread_a"], "RESTORED")
        self.readback["reports"] = {"threadA": self.reports["thread_a"], "postA": self.reports["post_a"], "threadB": self.reports["thread_b"]}

    def report_parent_and_author_deletion_guards(self) -> None:
        admin = self.admin
        other = self.identities.get("other")
        ordinary = self.identities.get("ordinary")
        moderator = self.identities.get("moderator")
        if admin is None or other is None or ordinary is None or moderator is None or not self.reports:
            raise GateFailure("report guard prerequisites were not initialized")
        # Hide the parent and then prove a child cannot be restored while its
        # parent remains hidden.  Restore the parent first, then restore child.
        parent_report = self.create_report(moderator, "THREAD", self.threads["a"], "parent-cycle")
        self.reports["parent"] = int(parent_report["id"])
        response, _ = self.resolve_report(admin, self.reports["post_a"], "HIDE", "M4 hide child")
        self._status(response, 200, "hide child post")
        response, _ = self.resolve_report(admin, self.reports["parent"], "HIDE", "M4 hide parent")
        self._status(response, 200, "hide parent thread")
        response, _ = self.resolve_report(admin, self.reports["post_a"], "RESTORE", "M4 restore child while parent hidden")
        self._status(response, 404, "restore child while parent hidden")
        response, _ = self.resolve_report(admin, self.reports["parent"], "RESTORE", "M4 restore parent")
        self._status(response, 200, "restore parent thread")
        response, _ = self.resolve_report(admin, self.reports["post_a"], "RESTORE", "M4 restore child after parent")
        self._status(response, 200, "restore child after parent")
        deleted = self.create_thread(ordinary, int(self.sections["a"]["id"]), "author-deleted")
        deleted_id = int(deleted["id"])
        report = self.create_report(other, "THREAD", deleted_id, "author-deleted")
        report_id = int(report["id"])
        response, _ = self.resolve_report(admin, report_id, "HIDE", "M4 hide author-deleted candidate")
        self._status(response, 200, "hide author-deleted candidate")
        response, _ = self.delete_thread(ordinary, deleted_id)
        self._status(response, 204, "author delete thread")
        response, _ = self.resolve_report(admin, report_id, "RESTORE", "M4 restore deleted author content")
        self._status(response, {400, 404, 409}, "restore author-deleted content")
        rows = self.runtime.sql(f"SELECT status FROM threads WHERE id={deleted_id}")
        if len(rows) != 1 or not rows[0] or rows[0][0] != "DELETED":
            raise GateFailure("author-deleted thread did not remain DELETED in SQL")
        self.threads["deleted"] = deleted_id
        self.reports["deleted"] = report_id
        # The beta report is now still pending.  Grant beta to the moderator,
        # resolve it there, revoke beta, and prove the scope denial immediately.
        beta_id = int(self.sections["b"]["id"])
        self.manage_user(admin, moderator.user_id, "moderator-sections", {"sectionIds": [int(self.sections["a"]["id"]), beta_id], "reason": "M4 grant beta scope"}, "grant moderator beta scope")
        moderator = self.login_identity(moderator, "moderator beta scope")
        self.identities["moderator"] = moderator
        response, _ = self.resolve_report(moderator, self.reports["thread_b"], "HIDE", "M4 moderator beta hide")
        self._status(response, 200, "moderator hide beta report")
        self.manage_user(admin, moderator.user_id, "moderator-sections", {"sectionIds": [int(self.sections["a"]["id"])], "reason": "M4 revoke beta scope"}, "revoke moderator beta scope")
        moderator = self.login_identity(moderator, "moderator after beta revoke")
        self.identities["moderator"] = moderator
        response, _ = self.resolve_report(moderator, self.reports["thread_b"], "RESTORE", "M4 cross-section restore")
        self._status(response, 403, "moderator cross-section restore")
        response, _ = self.resolve_report(admin, self.reports["thread_b"], "RESTORE", "M4 admin beta restore")
        self._status(response, 200, "admin beta restore")

    def report_search_and_db_readback(self) -> None:
        admin = self.admin
        if admin is None or "a" not in self.threads:
            raise GateFailure("search prerequisites were not initialized")
        marker = "M4 writable after lift"
        response, body = self._api_request("GET", "/threads", admin, params={"q": marker, "page": 1, "size": 20})
        if response.status_code == 200:
            value = self._dict(body, "thread search")
            items = value.get("items")
            if not isinstance(items, list) or not any(marker in str(item) for item in items):
                raise GateFailure("HTTP thread search did not return the current marker")
            self.readback["search"] = {"httpStatus": 200, "esProven": False, "evidence": "HTTP search result; Elasticsearch backend not independently proven"}
        elif response.status_code in {404, 501, 503}:
            self.readback["search"] = {"httpStatus": response.status_code, "esProven": False, "evidence": "DB current-content readback used because search service is unavailable"}
        else:
            self._status(response, 200, "thread search")
        rows = self.runtime.sql(f"SELECT status,title,content_md FROM threads WHERE id={self.threads['a']}")
        if len(rows) != 1 or len(rows[0]) != 3 or marker not in " ".join(rows[0][1:]):
            raise GateFailure("SQL current content readback did not contain the edited marker")
        self.readback["contentSqlReadback"] = {"threadId": self.threads["a"], "status": rows[0][0], "marker": marker}

    def audit_idempotency_and_sql_readback(self) -> None:
        admin = self.admin
        if admin is None or not self.reports:
            raise GateFailure("audit prerequisites were not initialized")
        audit_thread = self.create_thread(self.identities["ordinary"], int(self.sections["a"]["id"]), "audit-idempotency")
        self.threads["audit"] = int(audit_thread["id"])
        audit_report = self.create_report(self.identities["other"], "THREAD", self.threads["audit"], "audit-idempotency")
        self.reports["thread_audit"] = int(audit_report["id"])
        report_id = self.reports["thread_audit"]
        before = self.sql_report_audit_count(report_id, "REPORT_HIDE")
        response, _ = self.resolve_report(admin, report_id, "HIDE", "M4 idempotent hide")
        self._status(response, 200, "first idempotent hide")
        after_first = self.sql_report_audit_count(report_id, "REPORT_HIDE")
        response, _ = self.resolve_report(admin, report_id, "HIDE", "M4 idempotent hide")
        self._status(response, 200, "repeated idempotent hide")
        after = self.sql_report_audit_count(report_id, "REPORT_HIDE")
        if after_first != before + 1 or after != after_first:
            raise GateFailure("repeating a report action inserted a duplicate audit row")
        response, _ = self.resolve_report(admin, report_id, "RESTORE", "M4 restore after idempotency")
        self._status(response, 200, "restore after idempotency")
        target_type = "THREAD"
        target_id = self.threads.get("a")
        if target_id is None:
            raise GateFailure("alpha thread id was unavailable for audit readback")
        rows = self.runtime.sql(
            f"SELECT actor_id,reason,request_id FROM community_audit WHERE target_type={sql_literal(target_type)} AND target_id={target_id} ORDER BY occurred_at DESC LIMIT 20"
        )
        if not rows or not any(row[0] == str(admin.user_id) and row[1] for row in rows):
            raise GateFailure("audit SQL readback did not expose actor and reason")
        self.readback["audit"] = {"reportId": report_id, "hideRows": after, "lastRows": rows[:5]}

    def last_admin_revocation_and_restart(self) -> None:
        primary = self.primary_admin
        second = self.identities.get("secondadmin")
        if primary is None or second is None:
            raise GateFailure("admin revocation identities were not initialized")
        response, _ = self._api_request(
            "PUT", f"/governance/users/{primary.user_id}/admin", primary, json={"enabled": False, "reason": "M4 last-admin guard"}
        )
        self._status(response, 409, "disable last admin")
        self.manage_user(primary, second.user_id, "admin", {"enabled": True, "reason": "M4 second admin grant"}, "grant second admin")
        response, _ = self._api_request(
            "PUT", f"/governance/users/{primary.user_id}/admin", second, json={"enabled": False, "reason": "M4 primary admin revoke"}
        )
        self._status(response, 200, "revoke primary admin")
        immediate = self.governance(primary)
        raise_message = immediate.get("admin")
        if raise_message is not False:
            raise GateFailure("revoked admin token remained authorized immediately")
        self.runtime.restart_service("backend")
        self.refresh_urls()
        self.runtime.wait_http(f"http://127.0.0.1:{self.runtime.ports['backend']}/actuator/health", {200}, timeout=180)
        after_restart = self.governance(primary)
        if after_restart.get("admin") is not False:
            raise GateFailure("revoked admin permission was resurrected after backend restart")
        self.admin = self.login_identity(second, "second admin after restart")
        if self.governance(self.admin).get("admin") is not True:
            raise GateFailure("second admin did not remain authorized after restart")
        self.readback["adminRevocation"] = {"primaryId": primary.user_id, "secondId": second.user_id, "oldTokenAfterRestartAdmin": after_restart.get("admin")}

    def post_revocation_write_readback(self) -> None:
        admin = self.admin
        ordinary = self.identities.get("ordinary")
        if admin is None or ordinary is None:
            raise GateFailure("post-revocation identities were not initialized")
        response, _ = self._api_request("GET", "/governance/me", admin)
        self._status(response, 200, "active second admin governance")
        live = self.create_thread(ordinary, int(self.sections["a"]["id"]), "after-revocation")
        self.threads["afterRevocation"] = int(live["id"])
        response, _ = self.update_thread(ordinary, self.threads["afterRevocation"], content="M4-writable-after-recovery")
        self._status(response, 200, "ordinary write after admin revocation")
        rows = self.runtime.sql(f"SELECT status,title,content_md FROM threads WHERE id={self.threads['afterRevocation']}")
        if len(rows) != 1 or rows[0][0] != "NORMAL" or "M4-writable-after-recovery" not in " ".join(rows[0][1:]):
            raise GateFailure("post-revocation write was not durable in MySQL")
        self.readback["afterRevocationWrite"] = {"threadId": self.threads["afterRevocation"], "status": rows[0][0]}

    def _browser_init(self, page: Any, identity: Identity, theme: str) -> None:
        user_json = json.dumps(identity.user, ensure_ascii=False)
        token_json = json.dumps(identity.token)
        theme_json = json.dumps("dark" if theme == "night" else "light")
        page.add_init_script(
            script=(
                "localStorage.setItem('accessToken', %s);"
                "localStorage.setItem('currentUser', %s);"
                "localStorage.setItem('theme', %s);"
                "document.documentElement.classList.toggle('dark', %s === 'dark');"
                "document.documentElement.dataset.theme = %s;"
                "document.documentElement.style.colorScheme = %s;"
            )
            % (token_json, json.dumps(user_json), theme_json, theme_json, theme_json, theme_json)
        )

    def _assert_browser_layout(self, page: Any, label: str) -> None:
        overflow = page.evaluate("() => document.documentElement.scrollWidth > window.innerWidth + 1")
        if overflow:
            raise GateFailure(f"{label} has horizontal overflow")
        buttons = page.get_by_role("button")
        visible = 0
        for index in range(buttons.count()):
            button = buttons.nth(index)
            if not button.is_visible():
                continue
            visible += 1
            box = button.bounding_box()
            if box is None or box["height"] < 44 or box["width"] < 44:
                raise GateFailure(f"{label} has an interactive button smaller than 44px")
        if visible == 0:
            raise GateFailure(f"{label} rendered no accessible buttons")

    def _browser_community(self, viewport: str, theme: str) -> None:
        if self.admin is None:
            raise GateFailure("browser admin identity was not initialized")
        try:
            from playwright.sync_api import TimeoutError as PlaywrightTimeoutError, sync_playwright
        except ImportError as exc:  # pragma: no cover - CI dependency guard
            raise GateFailure("Playwright is required for M4 browser acceptance") from exc
        width, height = ((390, 844) if viewport == "mobile" else (844, 390))
        marker = f"M4 UI {secrets.token_hex(4)}"
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True)
            context = browser.new_context(viewport={"width": width, "height": height}, color_scheme="dark" if theme == "night" else "light")
            page = context.new_page()
            self._browser_init(page, self.admin, theme)
            try:
                page.goto(f"{self.frontend}/admin/community", wait_until="domcontentloaded", timeout=int(self.browser_timeout * 1000))
                page.get_by_role('heading', name='社区管理', exact=True).wait_for()
                if "无权" in page.locator("body").inner_text():
                    raise GateFailure("admin community UI reported access denied")
                forms = page.locator("form.governance-section-form")
                if forms.count() == 0:
                    raise GateFailure("admin community UI exposed no semantic section management form")
                form = forms.first
                name_field = form.get_by_label("名称", exact=True)
                slug_field = form.get_by_label("Slug", exact=True)
                reason_field = form.get_by_label(re.compile(r"变更理由", re.I), exact=False)
                if name_field.count() == 0 or slug_field.count() == 0 or reason_field.count() == 0:
                    raise GateFailure("admin community section form omitted labelled name/slug/reason fields")
                name_field.fill(marker)
                slug_field.fill("m4-ui-" + secrets.token_hex(4))
                reason_field.fill("M4 browser form submission")
                submit = form.get_by_role("button", name=re.compile(r"创建分区|保存分区|保存|创建|提交|更新|应用|Save|Create|Submit|Update", re.I))
                if submit.count() == 0 or not submit.first.is_visible():
                    raise GateFailure("admin community form exposed no semantic submit button")
                with page.expect_response(lambda response: "/api/v1/" in response.url and response.request.method in {"POST", "PUT"}, timeout=int(self.browser_timeout * 1000)):
                    submit.first.click()
                page.get_by_text('分区已创建。', exact=True).wait_for()
                if page.locator('html').evaluate("node => node.classList.contains('dark')") != (theme == 'night'):
                    raise GateFailure('community screenshot theme does not match its label')
                rows = self.get_sections(self.admin)
                if not any(marker in json.dumps(row, ensure_ascii=False) for row in rows):
                    raise GateFailure("community form submission did not read back through the sections API")
                self._assert_browser_layout(page, "admin community")
                page.screenshot(path=str(self.runtime.screenshot_dir / f"community-{viewport}-{theme}.png"), full_page=True)
            except PlaywrightTimeoutError as exc:
                raise GateFailure(f"admin community browser action timed out: {safe_text(exc)}") from exc
            finally:
                context.close()
                browser.close()

    def _browser_reports(self, viewport: str, theme: str) -> None:
        admin = self.admin
        other = self.identities.get("other")
        ordinary = self.identities.get("ordinary")
        if admin is None or other is None or ordinary is None:
            raise GateFailure("browser report identities were not initialized")
        thread = self.create_thread(ordinary, int(self.sections["a"]["id"]), f"browser-report-{secrets.token_hex(4)}")
        report = self.create_report(other, "THREAD", int(thread["id"]), "browser")
        report_id = int(report["id"])
        try:
            from playwright.sync_api import TimeoutError as PlaywrightTimeoutError, sync_playwright
        except ImportError as exc:  # pragma: no cover - CI dependency guard
            raise GateFailure("Playwright is required for M4 browser acceptance") from exc
        width, height = ((390, 844) if viewport == "mobile" else (844, 390))
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True)
            context = browser.new_context(viewport={"width": width, "height": height}, color_scheme="dark" if theme == "night" else "light")
            page = context.new_page()
            self._browser_init(page, admin, theme)
            page.on("dialog", lambda dialog: dialog.accept())
            try:
                page.goto(f"{self.frontend}/admin/reports", wait_until="domcontentloaded", timeout=int(self.browser_timeout * 1000))
                page.get_by_text(f'举报编号 #{report_id}', exact=True).wait_for()
                body_text = page.locator("body").inner_text()
                if str(report_id) not in body_text:
                    raise GateFailure(f"admin reports UI did not render pending report {report_id}")
                row = page.locator('li.ui-list-row').filter(has_text=f'举报编号 #{report_id}')
                row.get_by_role('button', name='隐藏内容', exact=True).click()
                row.get_by_label('隐藏内容理由（必填）', exact=True).fill('M4 browser reviewed spam')
                with page.expect_response(lambda response: "/admin/reports/" in response.url and response.request.method == "POST", timeout=int(self.browser_timeout * 1000)):
                    row.get_by_role('button', name='确认隐藏内容', exact=True).click()
                page.get_by_text('隐藏内容成功，列表已刷新。', exact=True).wait_for()
                self._report_item(self.list_reports(admin, "HIDDEN"), report_id, "HIDDEN")
                page.get_by_label('显示状态', exact=True).select_option('HIDDEN')
                page.get_by_role('button', name='应用筛选', exact=True).click()
                page.get_by_text(f'举报编号 #{report_id}', exact=True).wait_for()
                row.get_by_role('button', name='恢复内容', exact=True).click()
                row.get_by_label('恢复内容理由（必填）', exact=True).fill('M4 browser restore after review')
                with page.expect_response(lambda response: '/admin/reports/' in response.url and response.request.method == 'POST', timeout=int(self.browser_timeout * 1000)):
                    row.get_by_role('button', name='确认恢复内容', exact=True).click()
                page.get_by_text('恢复内容成功，列表已刷新。', exact=True).wait_for()
                self._report_item(self.list_reports(admin, 'RESTORED'), report_id, 'RESTORED')
                self.get_thread(ordinary, int(thread['id']))
                if page.locator('html').evaluate("node => node.classList.contains('dark')") != (theme == 'night'):
                    raise GateFailure('reports screenshot theme does not match its label')
                self._assert_browser_layout(page, "admin reports")
                page.screenshot(path=str(self.runtime.screenshot_dir / f"reports-{viewport}-{theme}.png"), full_page=True)
            except PlaywrightTimeoutError as exc:
                raise GateFailure(f"admin reports browser action timed out: {safe_text(exc)}") from exc
            finally:
                context.close()
                browser.close()

    def browser_case(self, page: str, viewport: str, theme: str) -> Callable[[], None]:
        if page == "community":
            return lambda: self._browser_community(viewport, theme)
        if page == "reports":
            return lambda: self._browser_reports(viewport, theme)
        raise ValueError(page)

    def write_readback(self) -> None:
        destination = self.runtime.artifact_dir / "community-readback.json"
        destination.write_text(json.dumps(self.readback, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_checks(runtime: ComposeRuntime, reporter: M4Reporter, started_at: dt.datetime, acceptance: CommunityAcceptance | None = None) -> None:
    statuses = [case.status for case in reporter.cases]
    status = "passed" if validate_report(reporter, runtime.cleanup_status) else "failed"
    checks: dict[str, Any] = {
        "project": runtime.project,
        "startedAt": started_at.isoformat(),
        "finishedAt": utc_now().isoformat(),
        "python": ".".join(str(part) for part in sys.version_info[:2]),
        "status": status,
        "executed": len(reporter.cases) > 0,
        "caseCount": len(reporter.cases),
        "expectedCases": len(PLANNED_CASES),
        "skipped": sum(case.status == "skipped" for case in reporter.cases),
        "failures": sum(case.status == "failed" for case in reporter.cases),
        "errors": sum(case.status == "error" for case in reporter.cases),
        "ports": runtime.ports,
        "browserOrigin": runtime.env_values.get("APP_CORS_ALLOWED_ORIGINS", ""),
        "cleanup": runtime.cleanup_status,
        "cases": [dataclasses.asdict(case) for case in reporter.cases],
        "plannedCases": list(PLANNED_CASES),
        "uncovered": [
            "Elasticsearch indexing is not independently proven; HTTP search or current MySQL readback is recorded.",
            "Cloud provider ACL/capacity and real mobile Safari/OS keyboard remain separate from this CI gate.",
        ],
    }
    if acceptance is not None:
        acceptance.write_readback()
        checks["readback"] = "community-readback.json"
        checks["screenshots"] = sorted(path.name for path in runtime.screenshot_dir.glob("*.png"))
    runtime.checks_file.write_text(json.dumps(checks, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    reporter.write_junit(runtime.junit_file)


def validate_report(reporter: M4Reporter, cleanup_status: str) -> bool:
    """Return success only for a non-empty, exact, non-skipped report."""

    if [case.name for case in reporter.cases] != list(PLANNED_CASES):
        return False
    if any(case.status not in {"passed"} for case in reporter.cases):
        return False
    return cleanup_status == "passed"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--artifact-dir",
        default=str(ROOT_DIR / "outputs" / "m4-runtime-ci"),
        help="parent directory for per-project checks, JUnit, logs, SQL readback, and screenshots",
    )
    parser.add_argument("--browser-timeout", type=float, default=90.0)
    args = parser.parse_args(argv)
    started_at = utc_now()
    reporter = M4Reporter()
    artifact_parent = Path(args.artifact_dir).resolve()
    outputs_root = (ROOT_DIR / "outputs").resolve()
    if artifact_parent == outputs_root or not artifact_parent.is_relative_to(outputs_root):
        print("ERROR: M4 artifacts must be stored under workspace outputs", file=sys.stderr)
        return 2
    artifact_parent.mkdir(parents=True, exist_ok=True)
    runtime: ComposeRuntime | None = None
    acceptance: CommunityAcceptance | None = None
    try:
        runtime = ComposeRuntime(artifact_parent)
        if reporter.run("bootstrap", runtime.bootstrap):
            if reporter.run("build", runtime.build) and reporter.run("start", runtime.start):
                acceptance = CommunityAcceptance(runtime, browser_timeout=args.browser_timeout)
                cases: list[tuple[str, Callable[[], None]]] = [
                    ("bootstrap-identities-and-admin-seeding", acceptance.bootstrap_identities_and_admin_seeding),
                    ("governance-shape-and-authorization", acceptance.governance_shape_and_authorization),
                    ("section-management-and-readback", acceptance.section_management_and_readback),
                    ("moderator-scope-and-cross-section-denial", acceptance.moderator_scope_and_cross_section_denial),
                    ("thread-post-inheritance-and-write-guards", acceptance.thread_post_inheritance_and_write_guards),
                    ("posting-restriction-and-archived-section", acceptance.posting_restriction_and_archived_section),
                    ("report-hide-restore-detail-list", acceptance.report_hide_restore_detail_list),
                    ("report-parent-and-author-deletion-guards", acceptance.report_parent_and_author_deletion_guards),
                    ("report-search-and-db-readback", acceptance.report_search_and_db_readback),
                    ("audit-idempotency-and-sql-readback", acceptance.audit_idempotency_and_sql_readback),
                    ("last-admin-revocation-and-restart", acceptance.last_admin_revocation_and_restart),
                    ("post-revocation-write-readback", acceptance.post_revocation_write_readback),
                ]
                for page in ("community", "reports"):
                    for viewport in ("mobile", "desktop"):
                        for theme in ("day", "night"):
                            cases.append((f"browser-{page}-{viewport}-{theme}", acceptance.browser_case(page, viewport, theme)))
                for name, action in cases:
                    reporter.run(name, action)
    except Exception as exc:  # pragma: no cover - mandatory artifact safety net
        reporter.cases.append(CaseResult("runner", "error", 0.0, safe_text(f"{type(exc).__name__}: {exc}")))
        if runtime is None:
            print("ERROR: unable to initialize isolated M4 runtime", file=sys.stderr)
            raise SystemExit(1) from exc
    finally:
        if runtime is not None:
            runtime.cleanup()
            if runtime.cleanup_status != "passed":
                reporter.cases.append(CaseResult("cleanup", "failed", 0.0, "owned Compose labels or private env cleanup failed"))
            try:
                write_checks(runtime, reporter, started_at, acceptance)
            except Exception as exc:  # pragma: no cover - artifact failure must be nonzero
                print(f"ERROR: unable to write M4 checks/JUnit artifacts: {safe_text(exc)}", file=sys.stderr)
                raise SystemExit(1)
            if not validate_report(reporter, runtime.cleanup_status):
                print(f"M4 community runtime failed: project={runtime.project} artifacts={runtime.artifact_dir}", file=sys.stderr)
                raise SystemExit(1)
            print(f"M4 community runtime passed: project={runtime.project} artifacts={runtime.artifact_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
