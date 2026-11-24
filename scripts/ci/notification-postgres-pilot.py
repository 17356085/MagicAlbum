#!/usr/bin/env python3
"""Run the notification MySQL -> PostgreSQL -> MySQL pilot acceptance flow.

The runner deliberately does not build Java.  It starts the supplied notification
JAR against disposable databases, exercises the real HTTP/SSE contract, pauses
the application around each copy, and writes only sanitized JSON evidence.
"""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import hashlib
import hmac
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from typing import Any


ROOT_DIR = Path(__file__).resolve().parents[2]
TABLES = ("notifications", "notification_stream_cursor", "notification_stream_event")
DEFAULT_POSTGRES_IMAGE = "postgres:16-alpine"
DEFAULT_MYSQL_IMAGE = "mysql:8.0"


class PilotFailure(RuntimeError):
    """A sanitized, named acceptance failure."""

    def __init__(self, check_name: str):
        super().__init__(check_name)
        self.check_name = check_name


def b64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def jwt_token(secret: str, issuer: str, user_id: int) -> str:
    now = int(time.time())
    header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    claims = b64url(json.dumps({
        "sub": str(user_id),
        "iss": issuer,
        "iat": now,
        "exp": now + 3600,
    }, separators=(",", ":")).encode())
    unsigned = f"{header}.{claims}".encode("ascii")
    signature = hmac.new(secret.encode("utf-8"), unsigned, hashlib.sha256).digest()
    return f"{header}.{claims}.{b64url(signature)}"


def sql_literal(value: object) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, int):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def docker_command(arguments: list[str], *, input_text: str | None = None, timeout: float = 60.0) -> subprocess.CompletedProcess[str]:
    """Run Docker without ever echoing its output (which can contain credentials)."""

    try:
        return subprocess.run(
            ["docker", *arguments],
            cwd=ROOT_DIR,
            input=input_text,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise PilotFailure("docker-command") from error


def docker_checked(arguments: list[str], *, input_text: str | None = None, timeout: float = 60.0) -> str:
    result = docker_command(arguments, input_text=input_text, timeout=timeout)
    if result.returncode != 0:
        raise PilotFailure("docker-command")
    return result.stdout.strip()


def mapped_port(container: str, container_port: int) -> int:
    output = docker_checked(["port", container, f"{container_port}/tcp"])
    match = re.search(r":(\d+)\s*$", output)
    if not match:
        raise PilotFailure("docker-port")
    return int(match.group(1))


class Database:
    def __init__(self, engine: str, container: str, port: int, database: str, user: str, password: str):
        self.engine = engine
        self.container = container
        self.port = port
        self.database = database
        self.user = user
        self.password = password

    def query(self, statement: str) -> list[list[str]]:
        if self.engine == "mysql":
            arguments = [
                "exec", "-i", "-e", f"MYSQL_PWD={self.password}", self.container,
                "mysql", "--protocol=tcp", "--host=127.0.0.1", f"--user={self.user}",
                f"--database={self.database}", "--batch", "--raw", "--skip-column-names",
            ]
        else:
            arguments = [
                "exec", "-i", "-e", f"PGPASSWORD={self.password}", self.container,
                "psql", "--host=127.0.0.1", f"--username={self.user}",
                f"--dbname={self.database}", "--tuples-only", "--no-align", "--field-separator=\t",
            ]
        result = docker_command(arguments, input_text=statement + "\n", timeout=30)
        if result.returncode != 0:
            raise PilotFailure("database-query")
        rows: list[list[str]] = []
        for line in result.stdout.splitlines():
            if line == "":
                continue
            rows.append(line.split("\t"))
        return rows

    def scalar(self, statement: str) -> str:
        rows = self.query(statement)
        if not rows or not rows[0]:
            raise PilotFailure("database-query-result")
        return rows[0][0]

    def count(self, table: str) -> int:
        if table not in TABLES:
            raise ValueError(table)
        return int(self.scalar(f"SELECT COUNT(*) FROM {table};"))


class NotificationPilot:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.run_id = secrets.token_hex(6)
        self.container_prefix = f"bluealbum-notification-pilot-{self.run_id}"
        self.containers: list[str] = []
        self.volumes: list[str] = []
        self.processes: list[subprocess.Popen[str]] = []
        self.log_dir = Path(tempfile.mkdtemp(prefix="bluealbum-notification-pilot-"))
        self.checks: list[dict[str, Any]] = []
        self.counts: dict[str, int] = {}
        self.copy_digests: dict[str, dict[str, Any]] = {}
        self.failure: str | None = None
        self.cleanup_errors: list[str] = []
        self.app: subprocess.Popen[str] | None = None
        self.app_port: int | None = None
        self.mysql: Database | None = None
        self.postgres: Database | None = None
        self.reverse_mysql: Database | None = None
        self.jwt_secret = secrets.token_urlsafe(48)
        self.internal_secret = secrets.token_urlsafe(48)
        self.jwt_issuer = "bluealbum-notification-pilot"
        self.user_id = 700_000_000 + (int(self.run_id, 16) % 900_000)
        self.concurrent_user_id = self.user_id + 1
        self.marker = f"pilot-{self.run_id}"
        self.first_payload: dict[str, Any] | None = None
        self.second_payload: dict[str, Any] | None = None
        self.first_id: int | None = None
        self.second_id: int | None = None
        self.post_cutover_id: int | None = None
        self.initial_events: list[dict[str, Any]] = []
        self.initial_cursor = 0
        self.post_cutover_cursor = 0
        self.concurrent_event_ids: list[int] = []
        self.token = jwt_token(self.jwt_secret, self.jwt_issuer, self.user_id)

    def record(self, name: str, passed: bool) -> None:
        if any(check["name"] == name for check in self.checks):
            return
        self.checks.append({"name": name, "passed": bool(passed)})

    def expect(self, name: str, condition: bool) -> None:
        self.record(name, condition)
        if not condition:
            raise PilotFailure(name)

    def fail(self, name: str) -> None:
        self.record(name, False)
        raise PilotFailure(name)

    def require_inputs(self) -> None:
        jar = Path(self.args.jar).expanduser().resolve()
        java = Path(self.args.java).expanduser().resolve() if os.path.sep in self.args.java or "/" in self.args.java else None
        self.expect("notification-jar-present", jar.is_file())
        if java is not None:
            self.expect("java-executable-present", java.is_file())
        if not self.args.postgres_profile:
            self.fail("postgres-profile-not-coordinated")

    def start_database(self, engine: str, role: str) -> Database:
        role_suffix = re.sub(r"[^a-z0-9-]", "-", role.lower())
        name = f"{self.container_prefix}-{role_suffix}"
        volume = f"{self.container_prefix}-{role_suffix}-volume"
        self.volumes.append(volume)
        self.containers.append(name)
        docker_checked(["volume", "create", volume])
        if engine == "mysql":
            database = f"ba_notify_{self.run_id}_{role_suffix.replace('-', '_')}"
            user = f"ba_notify_{self.run_id}_{role_suffix.replace('-', '_')}"
            password = secrets.token_urlsafe(36)
            arguments = [
                "run", "-d", "--name", name,
                "--label", f"com.bluealbum.notification-pilot={self.run_id}",
                "--mount", f"source={volume},target=/var/lib/mysql",
                "--publish", "127.0.0.1::3306",
                "--env", "MYSQL_ROOT_PASSWORD=" + secrets.token_urlsafe(36),
                "--env", f"MYSQL_DATABASE={database}",
                "--env", f"MYSQL_USER={user}",
                "--env", f"MYSQL_PASSWORD={password}",
                self.args.mysql_image,
            ]
            docker_checked(arguments, timeout=120)
            port = self.wait_mapped_port(name, 3306, role)
        else:
            database = f"ba_notify_{self.run_id}_{role_suffix.replace('-', '_')}"
            user = f"ba_notify_{self.run_id}_{role_suffix.replace('-', '_')}"
            password = secrets.token_urlsafe(36)
            arguments = [
                "run", "-d", "--name", name,
                "--label", f"com.bluealbum.notification-pilot={self.run_id}",
                "--mount", f"source={volume},target=/var/lib/postgresql/data",
                "--publish", "127.0.0.1::5432",
                "--env", f"POSTGRES_DB={database}",
                "--env", f"POSTGRES_USER={user}",
                "--env", f"POSTGRES_PASSWORD={password}",
                self.args.postgres_image,
            ]
            docker_checked(arguments, timeout=120)
            port = self.wait_mapped_port(name, 5432, role)
        database_handle = Database(engine, name, port, database, user, password)
        deadline = time.monotonic() + self.args.database_timeout
        while time.monotonic() < deadline:
            try:
                if database_handle.scalar("SELECT 1;") == "1":
                    self.record(f"{role}-database-ready", True)
                    return database_handle
            except PilotFailure:
                pass
            time.sleep(0.5)
        self.fail(f"{role}-database-ready")
        raise AssertionError("unreachable")

    def wait_mapped_port(self, container: str, container_port: int, role: str) -> int:
        """Docker Desktop can report a created container before its port map appears."""

        deadline = time.monotonic() + min(self.args.database_timeout, 30.0)
        while time.monotonic() < deadline:
            result = docker_command(["port", container, f"{container_port}/tcp"], timeout=15)
            if result.returncode == 0:
                match = re.search(r":(\d+)\s*$", result.stdout)
                if match:
                    return int(match.group(1))
            time.sleep(0.25)
        self.fail(f"{role}-database-port")
        raise AssertionError("unreachable")

    def app_environment(self, database: Database) -> tuple[dict[str, str], str, str]:
        environment = dict(os.environ)
        blocked = (
            "DB_HOST", "DB_PORT", "DB_NAME", "DB_USER", "DB_PASSWORD",
            "NOTIFICATION_DB_HOST", "NOTIFICATION_DB_PORT", "NOTIFICATION_DB_NAME",
            "NOTIFICATION_DB_USER", "NOTIFICATION_DB_PASSWORD", "NOTIFICATION_DATASOURCE_URL",
            "NOTIFICATION_DATASOURCE_USERNAME", "NOTIFICATION_DATASOURCE_PASSWORD",
            "NOTIFICATION_DATASOURCE_DRIVER", "NOTIFICATION_JWT_SECRET", "NOTIFICATION_JWT_ISSUER",
            "NOTIFICATION_POSTGRES_DATASOURCE_URL", "NOTIFICATION_POSTGRES_DATASOURCE_USERNAME",
            "NOTIFICATION_POSTGRES_DATASOURCE_PASSWORD",
            "NOTIFICATION_INTERNAL_SERVICE_TOKEN", "JWT_SECRET", "JWT_ISSUER", "INTERNAL_SERVICE_TOKEN",
            "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD",
            "SPRING_DATASOURCE_DRIVER_CLASS_NAME", "SPRING_PROFILES_ACTIVE", "SPRING_FLYWAY_USER",
            "SPRING_FLYWAY_PASSWORD", "SPRING_FLYWAY_URL", "NACOS_SERVER_ADDR",
        )
        for key in blocked:
            environment.pop(key, None)
        if database.engine == "mysql":
            jdbc_url = (
                f"jdbc:mysql://127.0.0.1:{database.port}/{database.database}"
                "?useSSL=false&serverTimezone=UTC&forceConnectionTimeZoneToSession=true"
                "&allowPublicKeyRetrieval=true&characterEncoding=utf8"
            )
            driver = "com.mysql.cj.jdbc.Driver"
        else:
            jdbc_url = f"jdbc:postgresql://127.0.0.1:{database.port}/{database.database}?sslmode=disable"
            driver = "org.postgresql.Driver"
        values = {
            "NOTIFICATION_DB_HOST": "127.0.0.1",
            "NOTIFICATION_DB_PORT": str(database.port),
            "NOTIFICATION_DB_NAME": database.database,
            "NOTIFICATION_DB_USER": database.user,
            "NOTIFICATION_DB_PASSWORD": database.password,
            "NOTIFICATION_DATASOURCE_URL": jdbc_url,
            "NOTIFICATION_DATASOURCE_USERNAME": database.user,
            "NOTIFICATION_DATASOURCE_PASSWORD": database.password,
            "NOTIFICATION_DATASOURCE_DRIVER": driver,
            "NOTIFICATION_POSTGRES_DATASOURCE_URL": jdbc_url,
            "NOTIFICATION_POSTGRES_DATASOURCE_USERNAME": database.user,
            "NOTIFICATION_POSTGRES_DATASOURCE_PASSWORD": database.password,
            "SPRING_DATASOURCE_URL": jdbc_url,
            "SPRING_DATASOURCE_USERNAME": database.user,
            "SPRING_DATASOURCE_PASSWORD": database.password,
            "SPRING_DATASOURCE_DRIVER_CLASS_NAME": driver,
            "NOTIFICATION_JWT_SECRET": self.jwt_secret,
            "NOTIFICATION_JWT_ISSUER": self.jwt_issuer,
            "NOTIFICATION_INTERNAL_SERVICE_TOKEN": self.internal_secret,
            "JWT_SECRET": self.jwt_secret,
            "JWT_ISSUER": self.jwt_issuer,
            "INTERNAL_SERVICE_TOKEN": self.internal_secret,
        }
        environment.update(values)
        return environment, jdbc_url, driver

    def start_app(self, database: Database, profile: str) -> None:
        if self.app is not None:
            self.fail("application-not-already-running")
        jar = str(Path(self.args.jar).expanduser().resolve())
        java = self.args.java
        self.app_port = free_port()
        log_path = self.log_dir / f"notification-{len(self.processes)}.log"
        error_path = self.log_dir / f"notification-{len(self.processes)}.err.log"
        log_file = log_path.open("w", encoding="utf-8")
        error_file = error_path.open("w", encoding="utf-8")
        environment, jdbc_url, driver = self.app_environment(database)
        profiles = [part.strip() for part in profile.split(",") if part.strip()]
        if "local" not in profiles:
            profiles.insert(0, "local")
        arguments = [
            java, "-Xms32m", "-Xmx384m", "-XX:ActiveProcessorCount=2", "-jar", jar,
            f"--spring.profiles.active={','.join(profiles)}", "--spring.config.import=",
            f"--server.port={self.app_port}", "--server.address=127.0.0.1",
            "--spring.cloud.nacos.config.enabled=false", "--spring.cloud.nacos.discovery.enabled=false",
            f"--spring.datasource.url={jdbc_url}", f"--spring.datasource.username={database.user}",
            f"--spring.datasource.password={database.password}", f"--spring.datasource.driver-class-name={driver}",
            "--spring.flyway.enabled=true", "--spring.flyway.clean-disabled=true",
            "--management.endpoints.web.exposure.include=health,info",
        ]
        try:
            process = subprocess.Popen(
                arguments,
                cwd=ROOT_DIR,
                env=environment,
                stdin=subprocess.DEVNULL,
                stdout=log_file,
                stderr=error_file,
                text=True,
            )
        except (OSError, ValueError) as error:
            log_file.close()
            error_file.close()
            raise PilotFailure("notification-application-start") from error
        finally:
            log_file.close()
            error_file.close()
        self.app = process
        self.processes.append(process)
        deadline = time.monotonic() + self.args.startup_timeout
        while time.monotonic() < deadline:
            if process.poll() is not None:
                self.fail("notification-application-ready")
            try:
                body = self.http_request("GET", "/actuator/health", expected=200, token=None, include_api_prefix=False)
                if isinstance(body, dict) and body.get("status") == "UP":
                    self.record(f"notification-{database.engine}-application-ready", True)
                    return
            except PilotFailure:
                pass
            time.sleep(0.5)
        self.fail("notification-application-ready")

    def stop_app(self) -> None:
        process = self.app
        self.app = None
        if process is None:
            return
        if process.poll() is None:
            try:
                process.terminate()
                process.wait(timeout=20)
            except (OSError, subprocess.TimeoutExpired):
                try:
                    process.kill()
                    process.wait(timeout=10)
                except (OSError, subprocess.TimeoutExpired) as error:
                    self.cleanup_errors.append("application-process")
        if process.poll() is None:
            self.cleanup_errors.append("application-process")

    def http_request(
        self,
        method: str,
        path: str,
        *,
        expected: int,
        token: str | None,
        body: dict[str, Any] | None = None,
        service: bool = False,
        include_api_prefix: bool = True,
    ) -> Any:
        if self.app_port is None:
            raise PilotFailure("notification-application-port")
        request_path = f"/api/v1{path}" if include_api_prefix else path
        url = f"http://127.0.0.1:{self.app_port}{request_path}"
        headers = {"Accept": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if service:
            headers["X-Internal-Service-Token"] = self.internal_secret
        payload = None
        if body is not None:
            payload = json.dumps(body, separators=(",", ":")).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url, data=payload, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=self.args.http_timeout) as response:
                status = int(response.status)
                content = response.read()
        except urllib.error.HTTPError as error:
            status = int(error.code)
            try:
                error.read()
            except OSError:
                pass
            content = b""
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise PilotFailure(f"http-{method.lower()}-{request_path}") from error
        if status != expected:
            raise PilotFailure(f"http-{method.lower()}-{request_path}-{status}")
        if not content:
            return None
        try:
            return json.loads(content.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise PilotFailure(f"http-{method.lower()}-{request_path}-json") from error

    def post_internal(self, payload: dict[str, Any]) -> dict[str, Any]:
        body = self.http_request("POST", "/notifications/internal", expected=201, token=None, body=payload, service=True)
        if not isinstance(body, dict) or not isinstance(body.get("id"), int):
            self.fail("internal-notification-response")
        return body

    def list_notifications(self, unread: bool | None = None) -> list[dict[str, Any]]:
        params = {"page": "1", "size": "100"}
        if unread is not None:
            params["unread"] = "true" if unread else "false"
        body = self.http_request("GET", "/notifications?" + urllib.parse.urlencode(params), expected=200, token=self.token)
        if not isinstance(body, dict) or not isinstance(body.get("items"), list):
            self.fail("notification-list-response")
        return body["items"]

    def list_events(self, user_id: int | None = None, after: int = 0) -> list[dict[str, Any]]:
        token = self.token if user_id in (None, self.user_id) else jwt_token(self.jwt_secret, self.jwt_issuer, user_id)
        body = self.http_request("GET", f"/notifications/events?after={after}&limit=100", expected=200, token=token)
        if not isinstance(body, list):
            self.fail("notification-events-response")
        return body

    def stream_event(self, cursor: int) -> dict[str, Any]:
        if self.app_port is None:
            self.fail("notification-application-port")
        connection = http.client.HTTPConnection("127.0.0.1", self.app_port, timeout=self.args.http_timeout)
        try:
            connection.request("GET", "/api/v1/notifications/stream", headers={
                "Authorization": f"Bearer {self.token}",
                "Accept": "text/event-stream",
                "Last-Event-ID": str(cursor),
            })
            response = connection.getresponse()
            self.expect("sse-http-response", response.status == 200)
            content_type = response.getheader("Content-Type", "")
            self.expect("sse-event-content-type", content_type.startswith("text/event-stream"))
            fields: dict[str, str] = {}
            data: list[str] = []
            while True:
                line = response.readline()
                if not line:
                    self.fail("sse-event-before-close")
                decoded = line.decode("utf-8").rstrip("\r\n")
                if decoded == "":
                    if data:
                        try:
                            fields["data"] = "\n".join(data)
                            fields["payload"] = json.dumps(json.loads(fields["data"]), separators=(",", ":"))
                        except (UnicodeDecodeError, json.JSONDecodeError) as error:
                            raise PilotFailure("sse-json") from error
                        return fields
                    fields = {}
                    data = []
                    continue
                if decoded.startswith(":"):
                    continue
                key, separator, value = decoded.partition(":")
                if not separator:
                    continue
                value = value[1:] if value.startswith(" ") else value
                if key == "data":
                    data.append(value)
                elif key in {"id", "event"}:
                    fields[key] = value
        except (http.client.HTTPException, OSError) as error:
            raise PilotFailure("sse-request") from error
        finally:
            connection.close()

    def seed_source(self) -> None:
        if self.mysql is None:
            self.fail("mysql-source-present")
        first_event = str(uuid.uuid4())
        second_event = str(uuid.uuid4())
        self.first_payload = {
            "eventId": first_event, "userId": self.user_id, "type": "comment",
            "title": f"{self.marker}-first", "content": "synthetic first notification",
            "threadId": 810001, "targetId": 810011, "targetType": "post",
        }
        self.second_payload = {
            "eventId": second_event, "userId": self.user_id, "type": "mention",
            "title": f"{self.marker}-second", "content": "synthetic second notification",
            "threadId": 810002, "targetId": 810012, "targetType": "thread",
        }
        first = self.post_internal(self.first_payload)
        second = self.post_internal(self.second_payload)
        self.first_id = int(first["id"])
        self.second_id = int(second["id"])
        self.expect("mysql-seed-writes", self.second_id > self.first_id)
        all_items = self.list_notifications()
        unread_items = self.list_notifications(True)
        self.expect("mysql-seed-read-unread-state", len(all_items) == 2 and len(unread_items) == 2)
        read = self.http_request("PATCH", f"/notifications/{self.first_id}/read", expected=200, token=self.token)
        self.expect("mysql-mark-read", isinstance(read, dict) and read.get("read") is True)
        unread_after = self.list_notifications(True)
        self.expect("mysql-unread-filter", len(unread_after) == 1 and int(unread_after[0]["id"]) == self.second_id)
        rows = self.mysql.query(
            "SELECT id,user_id,read_flag,idempotency_key FROM notifications ORDER BY id;"
        )
        self.expect(
            "mysql-seed-readback",
            len(rows) == 2 and int(rows[0][0]) == self.first_id and int(rows[0][1]) == self.user_id
            and rows[0][2] in {"1", "t"} and rows[1][2] in {"0", "f"},
        )
        self.initial_events = self.list_events()
        self.expect(
            "mysql-seed-cursors",
            len(self.initial_events) == 3
            and [event.get("kind") for event in self.initial_events] == ["created", "created", "read"],
        )
        self.initial_cursor = int(self.initial_events[-1]["cursor"])
        self.expect("mysql-original-cursor", self.initial_cursor > 0)
        self.expect(
            "mysql-original-cursor-readback",
            int(self.mysql.scalar(f"SELECT next_sequence FROM notification_stream_cursor WHERE user_id={self.user_id};"))
            == self.initial_cursor,
        )
        replay = self.post_internal(self.first_payload)
        self.expect("mysql-idempotent-replay", int(replay["id"]) == self.first_id)
        replay_events = self.list_events()
        self.expect(
            "mysql-replay-row-count",
            self.mysql.count("notifications") == 2 and len(replay_events) == len(self.initial_events),
        )
        conflict_payload = dict(self.first_payload)
        conflict_payload["title"] = f"{self.marker}-conflict"
        try:
            self.http_request(
                "POST", "/notifications/internal", expected=409, token=None,
                body=conflict_payload, service=True,
            )
        except PilotFailure:
            self.fail("mysql-idempotency-conflict")
        self.record("mysql-idempotency-conflict", True)
        self.counts.update({
            "mysql_seed_notifications": self.mysql.count("notifications"),
            "mysql_unread_after_read": len(unread_after),
            "mysql_seed_events": len(self.initial_events),
        })

    def check_schema(self, database: Database, check_name: str) -> None:
        try:
            row = database.query(
                "SELECT version,success FROM notification_flyway_schema_history "
                "ORDER BY installed_rank DESC LIMIT 1;"
            )
        except PilotFailure:
            self.fail(check_name)
        self.expect(check_name, len(row) == 1 and row[0][0] == "4" and row[0][1].lower() in {"1", "t", "true"})

    def check_empty_target(self, database: Database, name: str) -> None:
        values = [database.count(table) for table in TABLES]
        self.expect(name, values == [0, 0, 0])

    def run_copy(self, source: Database, target: Database, direction: str) -> dict[str, Any]:
        script = ROOT_DIR / "scripts" / "notification-data-copy.py"
        environment = dict(os.environ)
        source_prefix = "NOTIFICATION_COPY_SOURCE_"
        target_prefix = "NOTIFICATION_COPY_TARGET_"
        for prefix, database in ((source_prefix, source), (target_prefix, target)):
            environment.update({
                prefix + "HOST": "127.0.0.1",
                prefix + "PORT": str(database.port),
                prefix + "USER": database.user,
                prefix + "PASSWORD": database.password,
                prefix + "DATABASE": database.database,
            })
        try:
            result = subprocess.run(
                [sys.executable, str(script), "--source", source.engine, "--target", target.engine],
                cwd=ROOT_DIR,
                env=environment,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=self.args.copy_timeout,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            raise PilotFailure(f"{direction}-copy-command") from error
        if result.returncode != 0:
            raise PilotFailure(f"{direction}-copy-command")
        try:
            evidence = json.loads(result.stdout)
        except (json.JSONDecodeError, TypeError) as error:
            raise PilotFailure(f"{direction}-copy-json") from error
        tables = evidence.get("tables") if isinstance(evidence, dict) else None
        valid = isinstance(tables, dict)
        if valid:
            for table in TABLES:
                row = tables.get(table)
                valid = valid and isinstance(row, dict) and isinstance(row.get("rows"), int)
                valid = valid and isinstance(row.get("sha256"), str) and bool(re.fullmatch(r"[0-9a-f]{64}", row["sha256"]))
        self.expect(f"{direction}-copy-digest", bool(valid))
        self.copy_digests[direction] = tables
        return tables

    def verify_forward_copy(self) -> None:
        if self.postgres is None:
            self.fail("postgres-target-present")
        counts = [self.postgres.count(table) for table in TABLES]
        self.expect("postgres-forward-row-counts", counts == [2, 1, 3])
        rows = self.postgres.query("SELECT id,user_id,read_flag FROM notifications ORDER BY id;")
        self.expect(
            "postgres-forward-identity-read-state",
            len(rows) == 2 and int(rows[0][0]) == self.first_id and int(rows[0][1]) == self.user_id
            and rows[0][2].lower() in {"t", "true", "1"} and rows[1][2].lower() in {"f", "false", "0"},
        )
        self.expect(
            "postgres-forward-cursor-readback",
            int(self.postgres.scalar(f"SELECT next_sequence FROM notification_stream_cursor WHERE user_id={self.user_id};"))
            == self.initial_cursor,
        )
        self.counts.update({
            "postgres_forward_notifications": counts[0],
            "postgres_forward_cursors": counts[1],
            "postgres_forward_events": counts[2],
        })

    def verify_after_restart(self) -> None:
        if self.first_id is None or self.second_id is None:
            self.fail("seed-identities-present")
        items = self.list_notifications()
        self.expect(
            "postgres-http-readback",
            len(items) == 2 and any(int(item["id"]) == self.first_id and item.get("read") is True for item in items)
            and any(int(item["id"]) == self.second_id and item.get("read") is False for item in items),
        )
        first_cursor = int(self.initial_events[0]["cursor"])
        event = self.stream_event(first_cursor)
        try:
            payload = json.loads(event["data"])
        except (KeyError, json.JSONDecodeError) as error:
            raise PilotFailure("postgres-sse-replay") from error
        self.expect(
            "postgres-sse-last-event-id-after-restart",
            event.get("event") == "notification"
            and event.get("id") == str(self.initial_events[1]["cursor"])
            and payload.get("notificationId") == str(self.second_id),
        )

    def create_after_cutover(self) -> None:
        if self.postgres is None or self.initial_cursor <= 0:
            self.fail("postgres-cutover-state")
        payload = {
            "eventId": str(uuid.uuid4()), "userId": self.user_id, "type": "system",
            "title": f"{self.marker}-post-cutover", "content": "synthetic PostgreSQL write",
            "threadId": 810003, "targetId": 810013, "targetType": "thread",
        }
        created = self.post_internal(payload)
        self.post_cutover_id = int(created["id"])
        events = self.list_events(after=self.initial_cursor)
        self.expect("postgres-new-notification", len(events) == 1 and int(events[0]["notificationId"]) == self.post_cutover_id)
        self.post_cutover_cursor = int(events[0]["cursor"])
        self.expect("postgres-generated-id-and-cursor", self.post_cutover_id > int(self.second_id or 0) and self.post_cutover_cursor > self.initial_cursor)
        rows = self.postgres.query(
            f"SELECT id,user_id,read_flag FROM notifications WHERE id={self.post_cutover_id};"
        )
        self.expect("postgres-new-notification-readback", len(rows) == 1 and int(rows[0][1]) == self.user_id and rows[0][2].lower() in {"0", "f", "false"})
        replay = self.post_internal(payload)
        self.expect("postgres-idempotent-replay", int(replay["id"]) == self.post_cutover_id)
        replay_events = self.list_events(after=self.initial_cursor)
        self.expect(
            "postgres-idempotent-replay-no-event",
            len(replay_events) == 1
            and int(replay_events[0]["notificationId"]) == self.post_cutover_id
            and self.postgres.count("notification_stream_event") == 4
            and int(self.postgres.scalar(
                f"SELECT next_sequence FROM notification_stream_cursor WHERE user_id={self.user_id};"
            )) == self.post_cutover_cursor,
        )
        conflict_payload = dict(payload)
        conflict_payload["title"] = f"{self.marker}-post-cutover-conflict"
        try:
            self.http_request(
                "POST", "/notifications/internal", expected=409, token=None,
                body=conflict_payload, service=True,
            )
        except PilotFailure:
            self.fail("postgres-idempotency-conflict")
        self.record("postgres-idempotency-conflict", True)
        self.counts.update({"postgres_after_cutover_notifications": self.postgres.count("notifications")})

    def concurrent_first_cursor(self) -> None:
        payloads = []
        for index in range(2):
            payloads.append({
                "eventId": str(uuid.uuid4()), "userId": self.concurrent_user_id, "type": "system",
                "title": f"{self.marker}-concurrent-{index}", "content": "synthetic concurrent cursor write",
                "threadId": 820000 + index, "targetId": 820010 + index, "targetType": "thread",
            })
        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                results = list(pool.map(self.post_internal, payloads))
        except Exception as error:
            self.fail("postgres-concurrent-first-cursor")
            raise AssertionError("unreachable") from error
        self.concurrent_event_ids = [int(result["id"]) for result in results]
        events = self.list_events(self.concurrent_user_id)
        cursors = sorted(int(event["cursor"]) for event in events)
        self.expect("postgres-concurrent-first-cursor", cursors == [1, 2])
        self.counts["postgres_concurrent_first_cursor_events"] = len(events)

    def verify_reverse_copy(self) -> None:
        if self.reverse_mysql is None or self.first_id is None or self.second_id is None or self.post_cutover_id is None:
            self.fail("reverse-target-state")
        counts = [self.reverse_mysql.count(table) for table in TABLES]
        expected_notifications = 5
        expected_events = 6
        self.expect("mysql-reverse-row-counts", counts == [expected_notifications, 2, expected_events])
        rows = self.reverse_mysql.query(
            "SELECT id,user_id,read_flag FROM notifications ORDER BY id;"
        )
        state = {int(row[0]): (int(row[1]), row[2].lower()) for row in rows}
        self.expect(
            "mysql-reverse-identity-read-state",
            len(rows) == expected_notifications
            and state.get(self.first_id, (0, ""))[0] == self.user_id
            and state.get(self.first_id, (0, ""))[1] in {"1", "t", "true"}
            and state.get(self.second_id, (0, ""))[1] in {"0", "f", "false"}
            and state.get(self.post_cutover_id, (0, ""))[0] == self.user_id
            and all(state.get(event_id, (0, ""))[0] == self.concurrent_user_id for event_id in self.concurrent_event_ids),
        )
        self.counts.update({
            "mysql_reverse_notifications": counts[0],
            "mysql_reverse_cursors": counts[1],
            "mysql_reverse_events": counts[2],
        })

    def run(self) -> None:
        self.require_inputs()
        self.mysql = self.start_database("mysql", "source")
        self.start_app(self.mysql, "local")
        self.check_schema(self.mysql, "mysql-flyway-v4")
        self.seed_source()
        self.stop_app()

        self.postgres = self.start_database("postgresql", "target")
        self.start_app(self.postgres, self.args.postgres_profile)
        self.check_schema(self.postgres, "postgres-flyway-v4")
        self.check_empty_target(self.postgres, "postgres-target-empty-before-copy")
        self.stop_app()
        self.run_copy(self.mysql, self.postgres, "forward")
        self.verify_forward_copy()

        self.start_app(self.postgres, self.args.postgres_profile)
        self.verify_after_restart()
        self.create_after_cutover()
        self.concurrent_first_cursor()
        self.stop_app()

        self.reverse_mysql = self.start_database("mysql", "reverse")
        self.start_app(self.reverse_mysql, "local")
        self.check_schema(self.reverse_mysql, "mysql-reverse-flyway-v4")
        self.check_empty_target(self.reverse_mysql, "mysql-reverse-target-empty-before-copy")
        self.stop_app()
        self.run_copy(self.postgres, self.reverse_mysql, "reverse")
        self.verify_reverse_copy()

    def cleanup(self) -> None:
        self.stop_app()
        for container in reversed(self.containers):
            if not container.startswith(self.container_prefix + "-"):
                self.cleanup_errors.append("unexpected-container-target")
                continue
            removed = False
            for _ in range(3):
                result = docker_command(["rm", "-f", "-v", container], timeout=30)
                if result.returncode == 0:
                    removed = True
                    break
                time.sleep(0.5)
            if not removed:
                self.cleanup_errors.append("container-cleanup")
        for volume in reversed(self.volumes):
            if not volume.startswith(self.container_prefix + "-"):
                self.cleanup_errors.append("unexpected-volume-target")
                continue
            removed = False
            for _ in range(3):
                result = docker_command(["volume", "rm", volume], timeout=30)
                if result.returncode == 0:
                    removed = True
                    break
                time.sleep(0.5)
            if not removed:
                self.cleanup_errors.append("volume-cleanup")
        shutil.rmtree(self.log_dir, ignore_errors=True)

    def output_path(self) -> Path:
        path = Path(self.args.output).expanduser().resolve()
        if path.exists() and path.is_dir():
            return path / "notification-postgres-pilot.json"
        if str(self.args.output).endswith((os.path.sep, "/", "\\")):
            return path / "notification-postgres-pilot.json"
        return path

    def write_report(self, success: bool) -> Path:
        path = self.output_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "runId": self.run_id,
            "status": "passed" if success and not self.cleanup_errors else "failed",
            "postgresProfile": self.args.postgres_profile,
            "evidence": {
                "userId": self.user_id,
                "concurrentUserId": self.concurrent_user_id,
                "firstNotificationId": self.first_id,
                "secondNotificationId": self.second_id,
                "postCutoverNotificationId": self.post_cutover_id,
                "initialCursor": self.initial_cursor,
                "postCutoverCursor": self.post_cutover_cursor,
                "concurrentNotificationIds": self.concurrent_event_ids,
            },
            "checkNames": [check["name"] for check in self.checks],
            "checks": self.checks,
            "counts": self.counts,
            "copyDigests": self.copy_digests,
            "cleanup": "passed" if not self.cleanup_errors else "failed",
        }
        if self.failure:
            payload["failure"] = self.failure
        if self.cleanup_errors:
            payload["cleanupErrors"] = sorted(set(self.cleanup_errors))
        path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return path


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, help="already-built notification-service executable JAR")
    parser.add_argument("--java", required=True, help="Java executable used to launch the supplied JAR")
    parser.add_argument("--output", required=True, help="sanitized JSON report path (or directory)")
    parser.add_argument(
        "--postgres-profile",
        default=os.environ.get("NOTIFICATION_POSTGRES_PROFILE"),
        help="adapter profile coordinated with postgres_adapter; no profile is guessed",
    )
    parser.add_argument("--mysql-image", default=DEFAULT_MYSQL_IMAGE)
    parser.add_argument("--postgres-image", default=DEFAULT_POSTGRES_IMAGE)
    parser.add_argument("--database-timeout", type=float, default=120.0)
    parser.add_argument("--startup-timeout", type=float, default=120.0)
    parser.add_argument("--http-timeout", type=float, default=15.0)
    parser.add_argument("--copy-timeout", type=float, default=180.0)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    pilot = NotificationPilot(args)
    success = False
    try:
        pilot.run()
        success = True
    except PilotFailure as error:
        pilot.failure = error.check_name
    except Exception as error:  # noqa: BLE001 - report only the exception class
        pilot.failure = type(error).__name__
    finally:
        pilot.cleanup()
        try:
            output = pilot.write_report(success)
        except OSError:
            output = None
    if output is None:
        print("notification-postgres-pilot status=failed report=unavailable", file=sys.stderr)
        return 1
    final_success = success and not pilot.cleanup_errors
    print(f"notification-postgres-pilot status={'passed' if final_success else 'failed'} report={output}")
    return 0 if final_success else 1


if __name__ == "__main__":
    raise SystemExit(main())
