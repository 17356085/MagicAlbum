#!/usr/bin/env python3
"""M3 runtime acceptance gate for the isolated media Compose stack.

This is intentionally a real integration gate.  It builds and starts the
Compose services, drives the public HTTP/browser APIs, reads back SQL and
objects, and records a JUnit/checks artifact even when bootstrap fails.  The
script never prints generated credentials, bearer tokens, complete
environment values, or message/image payloads.
"""

from __future__ import annotations

import argparse
import base64
import dataclasses
import hashlib
import hmac
import json
import os
import re
import secrets
import struct
import subprocess
import sys
import threading
import time
import uuid
import xml.etree.ElementTree as ET
import zlib
from datetime import datetime, timezone
from pathlib import Path
from queue import Empty, Queue
from typing import Any, Callable
from urllib.parse import quote


ROOT_DIR = Path(__file__).resolve().parents[2]
BASE_COMPOSE = ROOT_DIR / "docker-compose.yml"
PYTHON_VERSION = sys.version_info[:2]


class GateFailure(RuntimeError):
    """A bounded, safe-to-report acceptance failure."""


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def safe_text(value: object) -> str:
    text = str(value).replace("\x00", "")
    text = re.sub(r"(?i)bearer\s+[A-Za-z0-9._-]+", "Bearer [redacted]", text)
    text = re.sub(r"(?i)(password|secret|token|authorization)\s*[=:]\s*[^\s,;]+", r"\1=[redacted]", text)
    return text[:600]


def mask_identifier(value: object) -> str:
    text = str(value)
    if len(text) <= 8:
        return "[id]"
    return f"{text[:4]}…{text[-4:]}"


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def make_png(width: int = 1601, height: int = 1601) -> bytes:
    """Build a deterministic real RGB PNG without Pillow or external tools."""

    if width <= 1600 or height <= 1600:
        raise ValueError("M3 fixture must exceed the preview edge")
    pixel = bytes((35, 126, 220))
    row = b"\x00" + pixel * width
    raw = row * height

    def chunk(kind: bytes, payload: bytes) -> bytes:
        return (
            struct.pack(">I", len(payload))
            + kind
            + payload
            + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
        )

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 6))
        + chunk(b"IEND", b"")
    )


def image_dimensions(data: bytes) -> tuple[int, int]:
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        if len(data) < 24 or data[12:16] != b"IHDR":
            raise GateFailure("PNG output has no valid IHDR")
        return struct.unpack(">II", data[16:24])
    if not data.startswith(b"\xff\xd8"):
        raise GateFailure("worker output is neither PNG nor JPEG")
    pos = 2
    while pos + 4 <= len(data):
        if data[pos] != 0xFF:
            pos += 1
            continue
        while pos < len(data) and data[pos] == 0xFF:
            pos += 1
        if pos >= len(data):
            break
        marker = data[pos]
        pos += 1
        if marker in (0xD8, 0xD9) or 0xD0 <= marker <= 0xD7:
            continue
        if pos + 2 > len(data):
            break
        segment_length = struct.unpack(">H", data[pos : pos + 2])[0]
        if segment_length < 2 or pos + segment_length > len(data):
            break
        if marker in set(range(0xC0, 0xC4)) | set(range(0xC5, 0xC8)) | set(range(0xC9, 0xCC)) | set(range(0xCD, 0xD0)):
            if segment_length < 7:
                break
            height, width = struct.unpack(">HH", data[pos + 3 : pos + 7])
            return width, height
        pos += segment_length
    raise GateFailure("JPEG output has no size marker")


def make_apng() -> bytes:
    image = make_png()
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    animation = chunk(b"acTL", struct.pack(">II", 1, 0))
    control = chunk(b"fcTL", struct.pack(">IIIIIHHBB", 0, 1601, 1601, 0, 0, 1, 10, 0, 0))
    return image[:33] + animation + control + image[33:]


def make_jwt(subject: int, secret: str, issuer: str, ttl_seconds: int) -> str:
    """Create only the short-lived test token needed for expiry coverage."""

    issued = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "sub": str(subject),
        "iss": issuer,
        "iat": issued,
        "exp": issued + ttl_seconds,
    }

    def encode(value: object) -> str:
        return base64.urlsafe_b64encode(
            json.dumps(value, separators=(",", ":"), ensure_ascii=False).encode()
        ).rstrip(b"=").decode()

    unsigned = f"{encode(header)}.{encode(payload)}"
    signature = hmac.new(secret.encode(), unsigned.encode(), hashlib.sha256).digest()
    return f"{unsigned}.{base64.urlsafe_b64encode(signature).rstrip(b'=').decode()}"


@dataclasses.dataclass
class CaseResult:
    name: str
    status: str
    duration_seconds: float
    detail: str = ""


class Reporter:
    def __init__(self) -> None:
        self.cases: list[CaseResult] = []

    def run(self, name: str, action: Callable[[], None]) -> bool:
        started = time.monotonic()
        try:
            action()
        except GateFailure as exc:
            self.cases.append(CaseResult(name, "failed", time.monotonic() - started, safe_text(exc)))
            return False
        except Exception as exc:  # pragma: no cover - integration-only safety net
            detail = safe_text(f"{type(exc).__name__}: {exc}")
            self.cases.append(CaseResult(name, "error", time.monotonic() - started, detail))
            return False
        self.cases.append(CaseResult(name, "passed", time.monotonic() - started))
        return True

    @property
    def failed(self) -> bool:
        return any(case.status != "passed" for case in self.cases)

    def write_junit(self, path: Path) -> None:
        suite = ET.Element(
            "testsuite",
            {
                "name": "bluealbum-m3-runtime",
                "tests": str(len(self.cases)),
                "failures": str(sum(case.status == "failed" for case in self.cases)),
                "errors": str(sum(case.status == "error" for case in self.cases)),
                "skipped": "0",
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
        path.write_bytes(ET.tostring(suite, encoding="utf-8", xml_declaration=True))


@dataclasses.dataclass
class Identity:
    username: str
    email: str
    phone: str
    password: str
    user_id: int
    token: str
    user: dict[str, Any]


class HttpApi:
    def __init__(self, base_url: str, default_headers: dict[str, str] | None = None) -> None:
        try:
            import httpx
        except ImportError as exc:  # pragma: no cover - CI dependency guard
            raise GateFailure("httpx is required by the M3 runtime gate") from exc
        self._httpx = httpx
        self.base_url = base_url.rstrip("/")
        self.client = httpx.Client(
            follow_redirects=False,
            timeout=httpx.Timeout(connect=8.0, read=30.0, write=30.0, pool=8.0),
            headers=default_headers or {},
        )

    def close(self) -> None:
        self.client.close()

    def request(self, method: str, path: str, **kwargs: Any) -> Any:
        return self.client.request(method, f"{self.base_url}{path}", **kwargs)

    def json_value(self, method: str, path: str, **kwargs: Any) -> tuple[Any, Any]:
        response = self.request(method, path, **kwargs)
        try:
            value = response.json()
        except Exception as exc:
            raise GateFailure(f"{method} {path} returned non-JSON status {response.status_code}") from exc
        return response, value

    def json(self, method: str, path: str, **kwargs: Any) -> tuple[Any, dict[str, Any]]:
        response, value = self.json_value(method, path, **kwargs)
        if not isinstance(value, dict):
            raise GateFailure(f"{method} {path} returned unexpected JSON shape")
        return response, value


class ComposeRuntime:
    def __init__(self, artifact_dir: Path) -> None:
        suffix = f"{int(time.time())}-{secrets.token_hex(4)}"
        self.project = f"bluealbum-m3-{suffix}"
        self.artifact_dir = artifact_dir / self.project
        self.artifact_dir.mkdir(parents=True, exist_ok=False)
        self.log_dir = self.artifact_dir / "logs"
        self.screenshot_dir = self.artifact_dir / "screenshots"
        self.log_dir.mkdir()
        self.screenshot_dir.mkdir()
        self.env_file = self.artifact_dir / "runtime.env"
        self.override_file = self.artifact_dir / "runtime.override.yml"
        self.checks_file = self.artifact_dir / "checks.json"
        self.junit_file = self.artifact_dir / "junit.xml"
        self.compose_touched = False
        self.cleanup_status = "not-run"
        self.ports: dict[str, int] = {}
        self.secrets: dict[str, str] = {
            "DB_PASSWORD": secrets.token_hex(32),
            "DB_ROOT_PASSWORD": secrets.token_hex(32),
            "REDIS_PASSWORD": secrets.token_hex(32),
            "RABBITMQ_PASSWORD": secrets.token_hex(32),
            "JWT_SECRET": secrets.token_hex(32),
            "INTERNAL_SERVICE_TOKEN": secrets.token_hex(32),
            "MEDIA_INTERNAL_SERVICE_TOKEN": secrets.token_hex(32),
            "S3_ACCESS_KEY": f"m3{secrets.token_hex(12)}",
            "S3_SECRET_KEY": secrets.token_hex(32),
        }
        self.env_values: dict[str, str] = {}
        self._write_env(public_base_url="http://127.0.0.1:1/media-test")
        self._write_override()

    def _write_env(self, public_base_url: str) -> None:
        values = {
            "DB_NAME": "bluealbum_m3",
            "NOTIFICATION_DB_NAME": "bluealbum_m3_notifications",
            "DB_USER": "bluealbum_m3",
            "RABBITMQ_USER": "bluealbum_m3",
            "JWT_ISSUER": "MagicAlbum",
            "APP_ADMIN_EMAIL": "",
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
            "MEDIA_AMQP_URL": f"amqp://bluealbum_m3:{self.secrets['RABBITMQ_PASSWORD']}@rabbitmq:5672/",
        }
        values.update(self.secrets)
        self.env_values = values
        text = "\n".join(f"{key}={value}" for key, value in values.items()) + "\n"
        self.env_file.write_text(text, encoding="utf-8", newline="\n")
        try:
            self.env_file.chmod(0o600)
        except OSError:
            pass

    def update_public_base_url(self, host_port: int) -> None:
        self._write_env(f"http://127.0.0.1:{host_port}/media-test")

    def update_browser_origin(self, host_port: int) -> None:
        if not 1 <= host_port <= 65535:
            raise GateFailure("invalid frontend origin port")
        self.env_values["APP_CORS_ALLOWED_ORIGINS"] = f"http://127.0.0.1:{host_port}"
        self._write_env(self.env_values["S3_PUBLIC_BASE_URL"])

    def _write_override(self) -> None:
        service_ports = {
            "mysql": ["127.0.0.1:0:3306"],
            "redis": ["127.0.0.1:0:6379"],
            "rabbitmq": ["127.0.0.1:0:5672", "127.0.0.1:0:15672"],
            "backend": ["127.0.0.1:0:8080"],
            "notification-service": ["127.0.0.1:0:8081"],
            "gateway": ["127.0.0.1:0:9000"],
            "frontend": ["127.0.0.1:0:80"],
            "media-worker": ["127.0.0.1:0:9091"],
            "s3mock": ["127.0.0.1:0:9090"],
        }
        fragments: dict[str, list[str]] = {
            "mysql": ["    mem_limit: 512m", "    memswap_limit: 512m"],
            "redis": ["    mem_limit: 192m", "    memswap_limit: 192m"],
            "rabbitmq": ["    mem_limit: 256m", "    memswap_limit: 256m"],
            "backend": [
                "    mem_limit: 768m",
                "    memswap_limit: 768m",
                "    environment:",
                '      JAVA_OPTS: "-Xmx512m -Xms256m -XX:ActiveProcessorCount=2"',
                '      MEDIA_PROCESSING_ENABLED: "true"',
                '      MEDIA_REGISTRATION_ENABLED: "true"',
                "      MEDIA_INTERNAL_SERVICE_TOKEN: ${MEDIA_INTERNAL_SERVICE_TOKEN}",
                "      APP_CORS_ALLOWED_ORIGINS: ${APP_CORS_ALLOWED_ORIGINS}",
                "      S3_BUCKET: media-test",
                "      S3_REGION: us-east-1",
                "      S3_ENDPOINT: http://s3mock:9090",
                "      S3_ACCESS_KEY: ${S3_ACCESS_KEY}",
                "      S3_SECRET_KEY: ${S3_SECRET_KEY}",
                "      S3_PUBLIC_BASE_URL: ${S3_PUBLIC_BASE_URL}",
            ],
            "notification-service": [
                "    mem_limit: 384m",
                "    memswap_limit: 384m",
                "    environment:",
                '      JAVA_OPTS: "-Xmx256m -Xms128m -XX:ActiveProcessorCount=2"',
            ],
            "gateway": [
                "    mem_limit: 384m",
                "    memswap_limit: 384m",
                "    environment:",
                '      JAVA_OPTS: "-Xmx256m -Xms128m -XX:ActiveProcessorCount=2"',
            ],
            "frontend": ["    mem_limit: 192m", "    memswap_limit: 192m"],
            "media-worker": [
                "    build:",
                "      context: ./services/media-service",
                "    mem_limit: 640m",
                "    memswap_limit: 640m",
                "    read_only: true",
                "    cap_drop: [ALL]",
                "    security_opt: [no-new-privileges:true]",
                "    environment:",
                "      MEDIA_CORE_URL: http://backend:8080",
                "      MEDIA_AMQP_URL: ${MEDIA_AMQP_URL}",
                "      MEDIA_INTERNAL_SERVICE_TOKEN: ${MEDIA_INTERNAL_SERVICE_TOKEN}",
                "      MEDIA_HEALTH_ADDR: :9091",
                '      GOMAXPROCS: "2"',
                "      GOMEMLIMIT: 384MiB",
            ],
            "s3mock": [
                "    image: adobe/s3mock:5.2.3",
                "    mem_limit: 512m",
                "    memswap_limit: 512m",
                "    environment:",
                "      COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS: media-test",
                "      COM_ADOBE_TESTING_S3MOCK_STORE_ROOT: /s3mockroot",
                '      COM_ADOBE_TESTING_S3MOCK_STORE_RETAIN_FILES_ON_EXIT: "true"',
                '      JAVA_TOOL_OPTIONS: "-Xmx256m -XX:ActiveProcessorCount=2"',
                "    volumes:",
                "      - m3s3mockdata:/s3mockroot",
            ],
        }
        blocks: list[str] = []
        for service, ports in service_ports.items():
            block = [f"  {service}:", "    ports: !override"]
            block.extend(f'      - "{port}"' for port in ports)
            block.extend(fragments.get(service, []))
            blocks.append("\n".join(block))
        text = "services:\n" + "\n".join(blocks) + "\n\nvolumes:\n  m3s3mockdata:\n"
        self.override_file.write_text(text, encoding="utf-8", newline="\n")

    @property
    def compose_prefix(self) -> list[str]:
        return [
            "docker",
            "compose",
            "--project-directory",
            str(ROOT_DIR),
            "--env-file",
            str(self.env_file),
            "--project-name",
            self.project,
            "--file",
            str(BASE_COMPOSE),
            "--file",
            str(self.override_file),
        ]

    def _child_env(self, extra: dict[str, str] | None = None) -> dict[str, str]:
        child = os.environ.copy()
        child["COMPOSE_DISABLE_ENV_FILE"] = "1"
        for key in self.env_values:
            child.pop(key, None)
        if extra:
            child.update(extra)
        return child

    def _redact(self, text: str) -> str:
        redacted = text
        for value in self.secrets.values():
            redacted = redacted.replace(value, "[redacted]")
        redacted = re.sub(r"(?i)bearer\s+[A-Za-z0-9._-]+", "Bearer [redacted]", redacted)
        redacted = re.sub(r"(?i)(password|secret|token)\s*[=:]\s*[^\s,;]+", r"\1=[redacted]", redacted)
        return redacted

    def _record_command_output(self, label: str, stdout: bytes | str, stderr: bytes | str) -> None:
        def normalize(value: bytes | str) -> str:
            if isinstance(value, bytes):
                value = value.decode("utf-8", "replace")
            return self._redact(value)[-10000:]

        output = normalize(stdout)
        error = normalize(stderr)
        if output or error:
            (self.log_dir / f"{re.sub(r'[^a-z0-9_-]+', '-', label.lower())}.log").write_text(
                (output + ("\n[stderr]\n" if output and error else "") + error).strip() + "\n",
                encoding="utf-8",
            )

    def compose(self, *args: str, timeout: float = 900.0, extra_env: dict[str, str] | None = None) -> str:
        command = self.compose_prefix + list(args)
        label = "compose-" + "-".join(args[:2])
        try:
            result = subprocess.run(
                command,
                cwd=ROOT_DIR,
                env=self._child_env(extra_env),
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
                timeout=timeout,
            )
        except subprocess.TimeoutExpired as exc:
            self._record_command_output(label, exc.stdout or b"", exc.stderr or b"")
            raise GateFailure(f"compose {' '.join(args[:2])} timed out") from exc
        self._record_command_output(label, result.stdout, result.stderr)
        if result.returncode != 0:
            raise GateFailure(f"compose {' '.join(args[:2])} failed with exit {result.returncode}")
        return result.stdout.decode("utf-8", "replace").strip()

    def docker(self, *args: str, timeout: float = 60.0) -> str:
        try:
            result = subprocess.run(
                ["docker", *args],
                cwd=ROOT_DIR,
                env=self._child_env(),
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
                timeout=timeout,
            )
        except subprocess.TimeoutExpired as exc:
            raise GateFailure(f"docker {' '.join(args[:2])} timed out") from exc
        if result.returncode != 0:
            raise GateFailure(f"docker {' '.join(args[:2])} failed with exit {result.returncode}")
        return result.stdout.decode("utf-8", "replace").strip()

    def assert_project_unused(self) -> None:
        for kind in ("container", "volume", "network"):
            list_args = [kind, "ls", "-q"]
            if kind == "container":
                list_args.insert(2, "-a")
            out = self.docker(*list_args, "--filter", f"label=com.docker.compose.project={self.project}")
            if out:
                raise GateFailure(f"Compose project label already has {kind}s")

    def refresh_ports(self) -> dict[str, int]:
        bindings = {
            "mysql": 3306,
            "redis": 6379,
            "rabbitmq": 5672,
            "rabbitmq-management": 15672,
            "backend": 8080,
            "notification-service": 8081,
            "gateway": 9000,
            "frontend": 80,
            "media-worker": 9091,
            "s3mock": 9090,
        }
        inventory = self.compose("ps", "--all", "--format", "json", timeout=30)
        rows = json.loads(inventory) if inventory.lstrip().startswith("[") else [json.loads(line) for line in inventory.splitlines() if line.strip()]
        running = {row["Service"] for row in rows if row.get("State") == "running"}
        ports: dict[str, int] = {}
        for service, private_port in bindings.items():
            compose_service = "rabbitmq" if service == "rabbitmq-management" else service
            if compose_service not in running:
                continue
            output = self.compose("port", "--index", "1", compose_service, str(private_port), timeout=30)
            match = re.search(r":(\d+)\s*$", output)
            if not match:
                raise GateFailure(f"could not read mapped host port for {service}")
            port = int(match.group(1))
            if port < 1 or port > 65535:
                raise GateFailure(f"invalid mapped host port for {service}")
            ports[service] = port
        self.ports = ports
        return ports

    def wait_http(self, url: str, expected: set[int], timeout: float = 180.0) -> None:
        try:
            import httpx
        except ImportError as exc:  # pragma: no cover
            raise GateFailure("httpx is required for health checks") from exc
        deadline = time.monotonic() + timeout
        last_status = 0
        while time.monotonic() < deadline:
            try:
                response = httpx.get(url, follow_redirects=False, timeout=5.0)
                last_status = response.status_code
                if response.status_code in expected:
                    return
            except Exception:
                pass
            time.sleep(1.0)
        raise GateFailure(f"health check did not become ready (last status {last_status})")

    def bootstrap(self) -> None:
        self.assert_project_unused()
        self.compose("config", "--quiet", timeout=90)
        self.compose_touched = True

    def build(self) -> None:
        self.compose("build", "backend", "notification-service", "gateway", "frontend", "media-worker", timeout=1800)

    def start(self) -> None:
        # Start S3Mock first so its dynamic host port can be embedded in the
        # backend's public URL before the Java container is created.
        self.compose("up", "-d", "s3mock", timeout=180)
        self.refresh_ports()
        s3_port = self.ports["s3mock"]
        self.wait_http(f"http://127.0.0.1:{s3_port}/favicon.ico", {200}, timeout=120)
        self.update_public_base_url(s3_port)
        self.compose("up", "-d", "--wait", "--wait-timeout", "300", timeout=900)
        self.refresh_ports()
        if self.ports["s3mock"] != s3_port:
            raise GateFailure("S3Mock host port changed while starting the remaining services")
        # The frontend uses an OS-assigned port. Recreate only the backend
        # after discovery so browser POSTs use that exact allowed origin.
        # Keep the frontend/S3 containers and their public URLs unchanged.
        frontend_port = self.ports["frontend"]
        self.update_browser_origin(frontend_port)
        self.compose("up", "-d", "--no-deps", "--wait", "--wait-timeout", "180", "backend", timeout=300)
        self.refresh_ports()
        if self.ports["frontend"] != frontend_port or self.ports["s3mock"] != s3_port:
            raise GateFailure("frontend or S3Mock host port changed while configuring browser origin")
        self.wait_http(f"http://127.0.0.1:{self.ports['s3mock']}/favicon.ico", {200}, timeout=60)
        self.wait_http(f"http://127.0.0.1:{self.ports['backend']}/actuator/health", {200}, timeout=180)
        self.wait_http(f"http://127.0.0.1:{self.ports['notification-service']}/actuator/health", {200}, timeout=180)
        self.wait_http(f"http://127.0.0.1:{self.ports['gateway']}/actuator/health", {200}, timeout=180)
        self.wait_http(f"http://127.0.0.1:{self.ports['frontend']}/", {200}, timeout=90)
        self.wait_http(f"http://127.0.0.1:{self.ports['media-worker']}/live", {200}, timeout=90)
        self.wait_http(f"http://127.0.0.1:{self.ports['media-worker']}/ready", {200}, timeout=180)

    def restart_service(self, service: str) -> None:
        self.compose("stop", service, timeout=90)
        self.compose("start", service, timeout=180)
        self.refresh_ports()

    def sql(self, query: str) -> list[list[str]]:
        # -e MYSQL_PWD passes the value from the child environment without
        # placing the credential in the process command line.
        output = self.compose(
            "exec",
            "-T",
            "-e",
            "MYSQL_PWD",
            "mysql",
            "mysql",
            "-uroot",
            "-D",
            self.env_values["DB_NAME"],
            "--batch",
            "--skip-column-names",
            "-e",
            query,
            timeout=60,
            extra_env={"MYSQL_PWD": self.secrets["DB_ROOT_PASSWORD"]},
        )
        rows: list[list[str]] = []
        for line in output.splitlines():
            if line:
                rows.append(["" if value == "NULL" else value for value in line.split("\t")])
        return rows

    def worker_env_keys(self) -> set[str]:
        container = self.compose("ps", "-q", "media-worker", timeout=30)
        if not container:
            raise GateFailure("media-worker container id was unavailable")
        constraints = self.docker("inspect", "--format",
            "{{.Config.User}}|{{.HostConfig.ReadonlyRootfs}}|{{.HostConfig.Memory}}|{{.HostConfig.MemorySwap}}|{{.State.OOMKilled}}", container).split("|")
        if (len(constraints) != 5 or constraints[0] in {"", "root", "0", "0:0", "root:root"}
                or constraints[1] != "true" or not 0 < int(constraints[2]) <= 640 * 1024 * 1024
                or constraints[2] != constraints[3] or constraints[4] != "false"):
            raise GateFailure("worker non-root/read-only/memory/OOM constraints were not met")
        raw = self.docker("inspect", "--format", "{{json .Config.Env}}", container)
        try:
            values = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise GateFailure("media-worker environment inspection was not JSON") from exc
        if not isinstance(values, list):
            raise GateFailure("media-worker environment inspection had unexpected shape")
        return {str(value).split("=", 1)[0] for value in values}

    def cleanup(self) -> None:
        status = "passed"
        try:
            if self.compose_touched:
                try:
                    self.compose("logs", "--no-color", "--timestamps", "--tail", "500", timeout=120)
                    # Keep separate tails: a noisy core/broker must not hide
                    # notification or proxy errors in the bounded combined log.
                    for service in ("backend", "notification-service", "gateway", "frontend", "media-worker"):
                        self.compose("logs", service, "--no-color", "--timestamps", "--tail", "150", timeout=30)
                except GateFailure:
                    status = "failed"
                try:
                    self.compose("down", "--volumes", "--remove-orphans", timeout=180)
                except GateFailure:
                    status = "failed"
                for kind in ("container", "volume", "network"):
                    try:
                        list_args = [kind, "ls", "-q"]
                        if kind == "container":
                            list_args.insert(2, "-a")
                        out = self.docker(*list_args, "--filter", f"label=com.docker.compose.project={self.project}")
                    except GateFailure:
                        status = "failed"
                        continue
                    if out:
                        status = "failed"
                        (self.log_dir / "cleanup-error.log").write_text(
                            f"Owned {kind}s remain for isolated project label\n", encoding="utf-8"
                        )
        finally:
            try:
                if self.env_file.exists():
                    self.env_file.unlink()
            except OSError:
                status = "failed"
            self.cleanup_status = status


class Acceptance:
    def __init__(self, runtime: ComposeRuntime, browser_timeout: float = 90.0) -> None:
        self.runtime = runtime
        self.browser_timeout = browser_timeout
        self.gateway = ""
        self.frontend = ""
        self.backend = ""
        self.notification = ""
        self.s3 = ""
        self.management = ""
        self.owner: Identity | None = None
        self.other: Identity | None = None
        self.fixture = make_png()
        self.fixture_sha = sha256_hex(self.fixture)
        self.success_asset: dict[str, Any] = {}
        self.success_status: dict[str, Any] = {}
        self.success_job: dict[str, Any] = {}

    def refresh_urls(self) -> None:
        ports = self.runtime.ports
        self.gateway = f"http://127.0.0.1:{ports['gateway']}"
        self.frontend = f"http://127.0.0.1:{ports['frontend']}"
        self.backend = f"http://127.0.0.1:{ports['backend']}"
        self.notification = f"http://127.0.0.1:{ports['notification-service']}"
        self.s3 = f"http://127.0.0.1:{ports['s3mock']}"
        self.management = f"http://127.0.0.1:{ports['rabbitmq-management']}"

    def health_and_isolation(self) -> None:
        self.refresh_urls()
        keys = self.runtime.worker_env_keys()
        forbidden = {
            "DB_HOST",
            "DB_PORT",
            "DB_NAME",
            "DB_USER",
            "DB_PASSWORD",
            "DB_ROOT_PASSWORD",
            "MYSQL_PWD",
            "S3_BUCKET",
            "S3_ENDPOINT",
            "S3_ACCESS_KEY",
            "S3_SECRET_KEY",
            "AWS_ACCESS_KEY_ID",
            "AWS_SECRET_ACCESS_KEY",
        }
        if keys & forbidden or any(key.startswith(("DB_", "S3_", "AWS_", "MYSQL_", "SPRING_DATASOURCE_")) for key in keys):
            raise GateFailure("media-worker environment contains forbidden database/storage keys")
        required = {"MEDIA_CORE_URL", "MEDIA_AMQP_URL", "MEDIA_INTERNAL_SERVICE_TOKEN", "GOMAXPROCS", "GOMEMLIMIT"}
        if not required.issubset(keys):
            raise GateFailure("media-worker environment is missing a required runtime key")
        self.runtime.sql("SELECT COUNT(*) FROM media_asset")

    def register_login(self, label: str) -> Identity:
        stamp = secrets.token_hex(5)
        username = f"m3_{label}_{stamp}"
        email = f"{username}@example.test"
        phone = "1" + str(secrets.randbelow(10**10)).zfill(10)
        password = "M3-runtime-" + secrets.token_urlsafe(18)
        api = HttpApi(self.gateway)
        try:
            response, body = api.json(
                "POST",
                "/api/v1/users/register",
                json={
                    "username": username,
                    "email": email,
                    "phone": phone,
                    "password": password,
                },
            )
            if response.status_code != 201 or not isinstance(body.get("id"), int):
                raise GateFailure(f"{label} registration returned status {response.status_code}")
            response, login = api.json(
                "POST",
                "/api/v1/auth/login",
                json={"email": email, "password": password},
            )
            token = login.get("accessToken")
            user = login.get("user")
            if response.status_code != 200 or not isinstance(token, str) or not token or not isinstance(user, dict):
                raise GateFailure(f"{label} login returned status {response.status_code}")
            user_id = user.get("id")
            if not isinstance(user_id, int) or user_id != body["id"]:
                raise GateFailure(f"{label} login identity readback failed")
            return Identity(username, email, phone, password, user_id, token, user)
        finally:
            api.close()

    def auth_identities(self) -> None:
        self.owner = self.register_login("owner")
        self.other = self.register_login("other")
        if self.owner.user_id == self.other.user_id:
            raise GateFailure("generated owner identities were not distinct")

    def auth_headers(self, identity: Identity) -> dict[str, str]:
        return {"Authorization": f"Bearer {identity.token}"}

    def upload(self, identity: Identity, content: bytes | None = None) -> dict[str, Any]:
        api = HttpApi(self.gateway)
        try:
            response, body = api.json(
                "POST",
                "/api/v1/uploads/images",
                headers=self.auth_headers(identity),
                files={"file": ("m3-fixture.png", self.fixture if content is None else content, "image/png")},
            )
            if response.status_code != 200:
                raise GateFailure(f"real PNG upload returned status {response.status_code}")
            for key in ("assetId", "url", "processingStatus", "jobId"):
                if key not in body:
                    raise GateFailure(f"upload response omitted {key}")
            return body
        finally:
            api.close()

    def asset_status(self, identity: Identity, asset_id: str) -> tuple[Any, dict[str, Any]]:
        api = HttpApi(self.gateway)
        try:
            return api.json("GET", f"/api/v1/uploads/assets/{asset_id}", headers=self.auth_headers(identity))
        finally:
            api.close()

    def poll_succeeded(self, identity: Identity, asset_id: str, timeout: float = 240.0) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        last = ""
        while time.monotonic() < deadline:
            response, body = self.asset_status(identity, asset_id)
            if response.status_code != 200:
                raise GateFailure(f"asset status returned {response.status_code}")
            status = str(body.get("processingStatus", ""))
            last = status
            if status == "SUCCEEDED":
                return body
            if status in {"FAILED", "CANCELLED"}:
                raise GateFailure(f"media job reached terminal {status}")
            time.sleep(2)
        raise GateFailure(f"media job did not succeed before timeout (last status {last})")

    def sql_asset(self, asset_id: str) -> dict[str, Any]:
        rows = self.runtime.sql(
            "SELECT asset_id,owner_id,provider,object_key,url,byte_size,sha256,status,processing_status "
            f"FROM media_asset WHERE asset_id='{asset_id}'"
        )
        if len(rows) != 1 or len(rows[0]) != 9:
            raise GateFailure("SQL media_asset readback returned an unexpected row count")
        row = rows[0]
        return {
            "asset_id": row[0],
            "owner_id": int(row[1]),
            "provider": row[2],
            "object_key": row[3],
            "url": row[4],
            "byte_size": int(row[5]),
            "sha256": row[6],
            "status": row[7],
            "processing_status": row[8],
        }

    def sql_job(self, asset_id: str, version: int | None = None) -> dict[str, Any]:
        where = f" AND job_version={version}" if version is not None else ""
        rows = self.runtime.sql(
            "SELECT job_id,asset_id,job_version,preset,processor_version,status,attempts,output_key,output_url,"
            "output_sha256,output_type,output_size,output_width,output_height,error_code "
            f"FROM media_processing_job WHERE asset_id='{asset_id}'{where} ORDER BY job_version DESC LIMIT 1"
        )
        if len(rows) != 1 or len(rows[0]) != 15:
            raise GateFailure("SQL media_processing_job readback returned an unexpected row count")
        row = rows[0]
        return {
            "job_id": row[0],
            "asset_id": row[1],
            "job_version": int(row[2]),
            "preset": row[3],
            "processor_version": row[4],
            "status": row[5],
            "attempts": int(row[6]),
            "output_key": row[7] or None,
            "output_url": row[8] or None,
            "output_sha256": row[9] or None,
            "output_type": row[10] or None,
            "output_size": int(row[11]) if row[11] else None,
            "output_width": int(row[12]) if row[12] else None,
            "output_height": int(row[13]) if row[13] else None,
            "error_code": row[14] or None,
        }

    def sql_envelope(self, job_id: str) -> dict[str, Any]:
        rows = self.runtime.sql(
            "SELECT event_id,asset_id,job_id,job_version,preset,processor_version,"
            "DATE_FORMAT(occurred_at,'%Y-%m-%dT%H:%i:%s.%fZ') "
            f"FROM media_request_outbox WHERE job_id='{job_id}' ORDER BY job_version DESC LIMIT 1"
        )
        if len(rows) != 1 or len(rows[0]) != 7:
            raise GateFailure("SQL media_request_outbox readback returned an unexpected row count")
        row = rows[0]
        envelope = {
            "schemaVersion": 1,
            "eventType": "MEDIA_PROCESSING_REQUESTED",
            "eventId": row[0],
            "assetId": row[1],
            "jobId": row[2],
            "jobVersion": int(row[3]),
            "preset": row[4],
            "processorVersion": row[5],
            "occurredAt": row[6],
        }
        if not all(isinstance(envelope[key], (str, int)) for key in envelope):
            raise GateFailure("SQL envelope contained an invalid value")
        return envelope

    def fetch_object(self, url: str, expected_hash: str) -> bytes:
        import httpx

        response = httpx.get(url, follow_redirects=False, timeout=30.0)
        if response.status_code != 200:
            raise GateFailure(f"S3 fixture object GET returned status {response.status_code}")
        data = response.content
        if sha256_hex(data) != expected_hash:
            raise GateFailure("S3 fixture object digest did not match SQL/upload digest")
        return data

    def internal_claim(self, envelope: dict[str, Any], token: str | None = None) -> tuple[Any, dict[str, Any]]:
        api = HttpApi(self.backend)
        try:
            headers = {"X-Internal-Service-Token": self.runtime.secrets["MEDIA_INTERNAL_SERVICE_TOKEN"] if token is None else token}
            response = api.request(
                "POST",
                f"/internal/media/v1/jobs/{envelope['jobId']}/claim",
                headers=headers,
                json=envelope,
            )
            try:
                return response, response.json()
            except ValueError:
                return response, {}
        finally:
            api.close()

    def happy_upload_and_readback(self) -> None:
        if self.owner is None or self.other is None:
            raise GateFailure("identities were not initialized")
        self.success_asset = self.upload(self.owner)
        asset_id = str(self.success_asset["assetId"])
        self.success_status = self.poll_succeeded(self.owner, asset_id)
        if self.success_status.get("assetId") != asset_id or self.success_status.get("processingStatus") != "SUCCEEDED":
            raise GateFailure("owner status did not report SUCCEEDED for the uploaded asset")
        asset_row = self.sql_asset(asset_id)
        self.success_job = self.sql_job(asset_id)
        if asset_row["owner_id"] != self.owner.user_id or asset_row["sha256"] != self.fixture_sha:
            raise GateFailure("SQL asset owner or original digest did not match the upload")
        if asset_row["status"] != "READY" or self.success_job["status"] != "SUCCEEDED":
            raise GateFailure("SQL asset/job status was not READY/SUCCEEDED")
        original = self.fetch_object(str(self.success_status["url"]), self.fixture_sha)
        if original != self.fixture:
            raise GateFailure("S3 original object bytes did not exactly match upload bytes")
        preview_url = self.success_status.get("previewUrl")
        if not isinstance(preview_url, str) or not preview_url:
            raise GateFailure("SUCCEEDED asset did not return previewUrl")
        preview = self.fetch_object(preview_url, str(self.success_job["output_sha256"]))
        width, height = image_dimensions(preview)
        if width > 1600 or height > 1600:
            raise GateFailure("preview dimensions exceeded the 1600px bound")
        if self.success_job["output_width"] != width or self.success_job["output_height"] != height:
            raise GateFailure("preview dimensions differed from SQL output metadata")
        if self.success_job["output_key"] == asset_row["object_key"]:
            raise GateFailure("derived output key reused the original object key")
        (self.runtime.artifact_dir / "media-readback.json").write_text(json.dumps({
            "assetId": asset_id, "jobId": self.success_job["job_id"], "status": self.success_job["status"],
            "originalSha256": self.fixture_sha, "previewSha256": self.success_job["output_sha256"],
            "previewBytes": len(preview), "width": width, "height": height,
        }, indent=2) + "\n", encoding="utf-8")

    def core_restart_readback(self) -> None:
        previous = self.success_job.copy()
        self.runtime.restart_service("backend")
        self.refresh_urls()
        self.runtime.wait_http(self.backend + "/actuator/health", {200}, timeout=180)
        state = self.poll_succeeded(self.owner, str(self.success_asset["assetId"]))
        current = self.sql_job(str(self.success_asset["assetId"]))
        if current != previous or state.get("jobId") != previous["job_id"]:
            raise GateFailure("core restart changed durable media metadata")
        self.fetch_object(str(state["url"]), self.fixture_sha)
        self.fetch_object(str(state["previewUrl"]), str(previous["output_sha256"]))

    def media_security(self) -> None:
        if self.owner is None or self.other is None:
            raise GateFailure("identities were not initialized")
        asset_id = str(self.success_asset["assetId"])
        response, _ = self.asset_status(self.other, asset_id)
        if response.status_code != 404:
            raise GateFailure(f"cross-owner asset status returned {response.status_code}, expected 404")
        envelope = self.sql_envelope(str(self.success_job["job_id"]))
        response, _ = self.internal_claim(envelope, token="")
        if response.status_code not in {401, 403}:
            raise GateFailure(f"internal media endpoint without token returned {response.status_code}")
        api = HttpApi(self.backend)
        try:
            response = api.request(
                "POST",
                f"/internal/media/v1/jobs/{envelope['jobId']}/claim",
                headers=self.auth_headers(self.owner),
                json=envelope,
            )
        finally:
            api.close()
        if response.status_code not in {401, 403}:
            raise GateFailure(f"ordinary user JWT reached internal media endpoint ({response.status_code})")
        # The S3 GET above is deliberately fixture evidence only.  It is not
        # treated as proof of AWS ACL/private-bucket authorization.
        if not str(self.success_status["url"]).startswith(self.s3):
            raise GateFailure("original URL did not point at the isolated S3Mock fixture")
        self.worker_auth_pause_recovery()

    def worker_auth_pause_recovery(self) -> None:
        """Wrong worker credentials pause consumption; repair reuses durable intent."""
        self.runtime.compose("stop", "media-worker", timeout=90)
        asset = self.upload(self.owner)
        asset_id = str(asset["assetId"])
        original = self.sql_job(asset_id)
        try:
            # Override only this worker recreation. The backend and private
            # generated environment retain their original, matching credential.
            self.runtime.compose(
                "up", "-d", "--no-deps", "--force-recreate", "media-worker", timeout=180,
                extra_env={"MEDIA_INTERNAL_SERVICE_TOKEN": secrets.token_hex(32)},
            )
            self.refresh_urls()
            worker = f"http://127.0.0.1:{self.runtime.ports['media-worker']}"
            self.runtime.wait_http(worker + "/live", {200}, timeout=60)
            deadline = time.monotonic() + 60
            paused_events = 0
            while time.monotonic() < deadline:
                paused_events = self.worker_auth_pause_events()
                if paused_events:
                    break
                time.sleep(0.5)
            if paused_events != 1:
                raise GateFailure("worker did not record exactly one credential pause")
            self.runtime.wait_http(worker + "/ready", {503}, timeout=15)
            row = self.sql_job(asset_id)
            if row["status"] != "PENDING" or row["attempts"] != 0 or row["output_key"]:
                raise GateFailure("worker credential failure mutated the unclaimed business job")
            # Longer than the retry queue TTL, so ordinary retry behavior would
            # have redelivered the message. The paused worker must stay detached.
            time.sleep(12)
            queue_response = self.rabbit_request("GET", "/api/queues/%2F/media.process.v1")
            if queue_response.status_code != 200:
                raise GateFailure("paused process queue readback failed")
            queue = queue_response.json()
            if queue.get("consumers") != 0 or queue.get("messages_ready", 0) < 1:
                raise GateFailure("credential-paused worker retained a consumer or lost durable work")
            if self.worker_auth_pause_events() != 1 or self.sql_job(asset_id) != row:
                raise GateFailure("credential-paused worker repeated handling or changed SQL state")
            self.runtime.wait_http(worker + "/ready", {503}, timeout=5)
            self.runtime.wait_http(worker + "/live", {200}, timeout=5)
        finally:
            self.runtime.compose("up", "-d", "--no-deps", "--force-recreate", "media-worker", timeout=180)
            self.refresh_urls()
        self.runtime.wait_http(f"http://127.0.0.1:{self.runtime.ports['media-worker']}/ready", {200}, timeout=90)
        final = self.poll_succeeded(self.owner, asset_id)
        completed = self.sql_job(asset_id)
        if completed["job_id"] != original["job_id"] or completed["attempts"] != 1 or completed["status"] != "SUCCEEDED":
            raise GateFailure("credential repair did not recover the original job exactly once")
        if self.fetch_object(str(final["url"]), self.fixture_sha) != self.fixture:
            raise GateFailure("credential recovery changed original image bytes")
        preview = self.fetch_object(str(final["previewUrl"]), str(completed["output_sha256"]))
        (self.runtime.artifact_dir / "worker-auth-pause.json").write_text(json.dumps({
            "assetId": asset_id, "jobId": completed["job_id"],
            "pausedReadyStatus": 503, "pausedLiveStatus": 200,
            "pauseEvents": paused_events, "observedPauseSeconds": 12, "pausedQueueConsumers": 0,
            "pausedJobAttempts": 0, "recoveredJobAttempts": completed["attempts"],
            "recoveredStatus": completed["status"], "originalSha256": self.fixture_sha,
            "previewSha256": completed["output_sha256"], "previewBytes": len(preview),
        }, indent=2) + "\n", encoding="utf-8")

    def worker_auth_pause_events(self) -> int:
        logs = self.runtime.compose("logs", "--no-color", "--tail", "100", "media-worker", timeout=30)
        count = 0
        for line in logs.splitlines():
            start = line.find("{")
            if start < 0:
                continue
            try:
                event = json.loads(line[start:])
            except ValueError:
                continue
            if event.get("event") == "consumer_paused" and event.get("category") == "core_auth_paused":
                count += 1
        return count

    def permanent_failure_keeps_original(self) -> None:
        content = make_apng()
        asset = self.upload(self.owner, content)
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            response, state = self.asset_status(self.owner, str(asset["assetId"]))
            if response.status_code != 200:
                raise GateFailure("failed media owner state was unavailable")
            if state.get("processingStatus") == "FAILED":
                row = self.sql_job(str(asset["assetId"]))
                if state.get("errorCode") != "ANIMATED_IMAGE" or row["error_code"] != "ANIMATED_IMAGE" or row["output_key"]:
                    raise GateFailure("animated image failure was not durably classified")
                if self.fetch_object(str(state["url"]), sha256_hex(content)) != content or state.get("previewUrl"):
                    raise GateFailure("permanent preview failure changed the original")
                return
            if state.get("processingStatus") == "SUCCEEDED":
                raise GateFailure("animated PNG incorrectly received a successful preview")
            time.sleep(2)
        raise GateFailure("animated PNG failure did not finish")

    def result_callback_idempotency(self) -> None:
        self.runtime.compose("stop", "media-worker", timeout=90)
        asset = self.upload(self.owner)
        job = self.sql_job(str(asset["assetId"]))
        envelope = self.sql_envelope(job["job_id"])
        response, lease = self.internal_claim(envelope)
        if response.status_code != 200 or lease.get("status") != "CLAIMED":
            raise GateFailure("callback fixture did not receive a real lease")
        preview = self.fetch_object(str(self.success_status["previewUrl"]), str(self.success_job["output_sha256"]))
        fields = {"jobVersion": "1", "inputSha256": self.fixture_sha,
                  "outputSha256": sha256_hex(preview), "processorVersion": "1",
                  "width": str(self.success_job["output_width"]), "height": str(self.success_job["output_height"])}
        headers = {"X-Internal-Service-Token": self.runtime.secrets["MEDIA_INTERNAL_SERVICE_TOKEN"],
                   "X-Media-Lease-Token": lease["leaseToken"]}
        api = HttpApi(self.backend)
        try:
            path = f"/internal/media/v1/jobs/{job['job_id']}/result"
            for attempt in range(2):
                response, result = api.json("POST", path, headers=headers, data=fields, files={"file": ("result.png", preview, "image/png")})
                if response.status_code != 200 or result.get("status") != "SUCCEEDED":
                    raise GateFailure(f"idempotent callback attempt {attempt + 1} failed")
            accepted = self.sql_job(str(asset["assetId"]))
            text = b"m3\x00conflicting-result"
            chunk = struct.pack(">I", len(text)) + b"tEXt" + text + struct.pack(">I", zlib.crc32(b"tEXt" + text) & 0xFFFFFFFF)
            conflicting = preview[:33] + chunk + preview[33:]
            response = api.request("POST", path, headers=headers, data={**fields, "outputSha256": sha256_hex(conflicting)},
                files={"file": ("result.png", conflicting, "image/png")})
            if response.status_code != 409 or self.sql_job(str(asset["assetId"])) != accepted:
                raise GateFailure("conflicting callback changed an accepted result")
        finally:
            api.close()
        self.fetch_object(str(accepted["output_url"]), sha256_hex(preview))
        self.fetch_object(str(asset["url"]), self.fixture_sha)
        self.runtime.compose("start", "media-worker", timeout=180)
        self.runtime.refresh_ports()
        self.runtime.wait_http(f"http://127.0.0.1:{self.runtime.ports['media-worker']}/ready", {200}, timeout=180)

    def cancel_and_retry(self) -> None:
        if self.owner is None:
            raise GateFailure("owner identity was not initialized")
        self.runtime.compose("stop", "media-worker", timeout=90)
        self.runtime.refresh_ports()
        pending_asset = self.upload(self.owner)
        asset_id = str(pending_asset["assetId"])
        initial_status = str(pending_asset.get("processingStatus"))
        if initial_status not in {"PENDING", "PROCESSING"}:
            raise GateFailure(f"worker-stopped upload was not pending ({initial_status})")
        first_job = self.sql_job(asset_id)
        first_envelope = self.sql_envelope(str(first_job["job_id"]))
        api = HttpApi(self.gateway)
        try:
            response, cancelled = api.json(
                "POST",
                f"/api/v1/uploads/assets/{asset_id}/cancel",
                headers=self.auth_headers(self.owner),
            )
            if response.status_code != 200 or cancelled.get("processingStatus") != "CANCELLED":
                raise GateFailure(f"owner cancel returned status {response.status_code}")
        finally:
            api.close()
        response, body = self.internal_claim(first_envelope)
        if response.status_code != 200 or body.get("status") != "CANCELLED":
            raise GateFailure("old cancelled job did not return terminal CANCELLED on claim")
        api = HttpApi(self.gateway)
        try:
            response, retried = api.json(
                "POST",
                f"/api/v1/uploads/assets/{asset_id}/retry",
                headers=self.auth_headers(self.owner),
            )
            if response.status_code != 200 or retried.get("processingStatus") != "PENDING":
                raise GateFailure(f"owner retry returned status {response.status_code}")
        finally:
            api.close()
        second_job = self.sql_job(asset_id)
        second_envelope = self.sql_envelope(str(second_job["job_id"]))
        if second_job["job_version"] != 2 or second_job["job_id"] == first_job["job_id"]:
            raise GateFailure("retry did not create a new job version and job id")
        if second_envelope["eventId"] == first_envelope["eventId"]:
            raise GateFailure("retry did not create a new outbox event")
        self.runtime.compose("start", "media-worker", timeout=180)
        self.runtime.refresh_ports()
        self.runtime.wait_http(f"http://127.0.0.1:{self.runtime.ports['media-worker']}/ready", {200}, timeout=180)
        final = self.poll_succeeded(self.owner, asset_id)
        if final.get("jobId") != second_job["job_id"] or final.get("processingStatus") != "SUCCEEDED":
            raise GateFailure("retry result did not become the latest successful job")

    def rabbit_request(self, method: str, path: str, **kwargs: Any) -> Any:
        import httpx

        url = f"{self.management}{path}"
        return httpx.request(
            method,
            url,
            auth=(self.runtime.env_values["RABBITMQ_USER"], self.runtime.secrets["RABBITMQ_PASSWORD"]),
            follow_redirects=False,
            timeout=20.0,
            **kwargs,
        )

    def rabbit_get(self, queue_name: str, count: int = 10) -> list[dict[str, Any]]:
        path = f"/api/queues/%2F/{quote(queue_name, safe='')}/get"
        response = self.rabbit_request(
            "POST",
            path,
            json={"count": count, "ackmode": "ack_requeue_true", "encoding": "auto", "truncate": 50000},
        )
        if response.status_code != 200:
            raise GateFailure(f"Rabbit management queue GET returned {response.status_code}")
        try:
            value = response.json()
        except Exception as exc:
            raise GateFailure("Rabbit management queue GET was not JSON") from exc
        if not isinstance(value, list):
            raise GateFailure("Rabbit management queue GET returned an unexpected shape")
        return [item for item in value if isinstance(item, dict)]

    def rabbit_publish(self, exchange: str, routing_key: str, body: bytes, message_id: str | None = None) -> None:
        path = f"/api/exchanges/%2F/{quote(exchange, safe='')}/publish"
        properties: dict[str, Any] = {"delivery_mode": 2, "content_type": "application/json"}
        if message_id:
            properties["message_id"] = message_id
        response = self.rabbit_request(
            "POST",
            path,
            json={
                "properties": properties,
                "routing_key": routing_key,
                "payload": body.decode("utf-8"),
                "payload_encoding": "string",
            },
        )
        if response.status_code != 200:
            raise GateFailure(f"Rabbit management publish returned {response.status_code}")
        try:
            value = response.json()
        except Exception as exc:
            raise GateFailure("Rabbit management publish was not JSON") from exc
        if not isinstance(value, dict) or value.get("routed") is not True:
            raise GateFailure("Rabbit management publish was not routed")

    def wait_queue_message(self, queue_name: str, predicate: Callable[[dict[str, Any]], bool], timeout: float = 90.0) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            for message in self.rabbit_get(queue_name, count=20):
                if predicate(message):
                    return message
            time.sleep(2)
        raise GateFailure(f"Rabbit queue {queue_name} did not expose the expected message")

    def mq_persistence_and_quarantine(self) -> None:
        if self.owner is None:
            raise GateFailure("owner identity was not initialized")
        # Keep the worker stopped so the actual published request remains in
        # Rabbit until the management API reads its properties.
        self.runtime.compose("stop", "media-worker", timeout=90)
        self.runtime.refresh_ports()
        durable_asset = self.upload(self.owner)
        durable_asset_id = str(durable_asset["assetId"])
        durable_job = self.sql_job(durable_asset_id)
        durable_env = self.sql_envelope(str(durable_job["job_id"]))
        durable = self.wait_queue_message(
            "media.process.v1",
            lambda item: item.get("properties", {}).get("delivery_mode") == 2
            and item.get("payload", "")
            and json.loads(item["payload"]).get("jobId") == durable_job["job_id"]
            and item.get("exchange") == "media.jobs.v1",
            timeout=120,
        )
        if durable.get("properties", {}).get("delivery_mode") != 2:
            raise GateFailure("process queue message was not persistent")
        self.runtime.compose("start", "media-worker", timeout=180)
        self.runtime.refresh_ports()
        self.runtime.wait_http(f"http://127.0.0.1:{self.runtime.ports['media-worker']}/ready", {200}, timeout=180)
        self.poll_succeeded(self.owner, durable_asset_id)

        # A duplicate real envelope is accepted by the terminal claim path and
        # must not change the durable success row.
        duplicate_body = json.dumps(durable_env, separators=(",", ":")).encode()
        expected_duplicate_output = self.sql_job(durable_asset_id)["output_sha256"]
        self.rabbit_publish("media.jobs.v1", "process", duplicate_body, durable_env["eventId"])
        self.rabbit_publish("media.jobs.v1", "process", duplicate_body, durable_env["eventId"])
        time.sleep(5)
        duplicate_job = self.sql_job(durable_asset_id)
        if duplicate_job["status"] != "SUCCEEDED" or duplicate_job["output_sha256"] != expected_duplicate_output:
            raise GateFailure("duplicate real MQ delivery changed the successful job")

        secret_marker = secrets.token_hex(32).encode()
        invalid_body = b'{"schemaVersion":1,"secret":"' + secret_marker + b"""","unexpected":true}"""
        self.rabbit_publish("media.jobs.v1", "process", invalid_body)
        quarantine = self.wait_queue_message(
            "media.quarantine.v1",
            lambda item: item.get("properties", {}).get("delivery_mode") == 2,
            timeout=90,
        )
        payload = quarantine.get("payload", "")
        if secret_marker.decode() in payload:
            raise GateFailure("quarantine message leaked invalid message text")
        try:
            quarantine_body = json.loads(payload)
        except (TypeError, json.JSONDecodeError) as exc:
            raise GateFailure("quarantine message was not JSON") from exc
        if set(quarantine_body) != {"digest", "reason"}:
            raise GateFailure("quarantine message contained fields beyond digest/reason")
        if quarantine_body.get("digest") != sha256_hex(invalid_body) or not re.fullmatch(
            r"[A-Za-z0-9_]+", str(quarantine_body.get("reason", ""))
        ):
            raise GateFailure("quarantine message digest/reason was invalid")

    def busy_retry_and_lease_recovery(self) -> None:
        if self.owner is None:
            raise GateFailure("owner identity was not initialized")
        self.runtime.compose("stop", "media-worker", timeout=90)
        self.runtime.refresh_ports()
        asset = self.upload(self.owner)
        asset_id = str(asset["assetId"])
        job = self.sql_job(asset_id)
        envelope = self.sql_envelope(str(job["job_id"]))
        response, claimed = self.internal_claim(envelope)
        if response.status_code != 200 or claimed.get("status") != "CLAIMED" or not claimed.get("leaseToken"):
            raise GateFailure("manual internal claim did not create a lease")
        self.runtime.compose("start", "media-worker", timeout=180)
        self.runtime.refresh_ports()
        self.runtime.wait_http(f"http://127.0.0.1:{self.runtime.ports['media-worker']}/ready", {200}, timeout=180)
        self.rabbit_publish("media.jobs.v1", "process", json.dumps(envelope, separators=(",", ":")).encode(), envelope["eventId"])
        retry = self.wait_queue_message(
            "media.retry.v1",
            lambda item: item.get("properties", {}).get("delivery_mode") == 2 and json.loads(item.get("payload", "{}")).get("jobId") == job["job_id"],
            timeout=90,
        )
        if retry.get("properties", {}).get("delivery_mode") != 2:
            raise GateFailure("retry queue message was not persistent")
        # Expire only this freshly generated lease.  Keep updated_at fresh:
        # an expired PROCESSING job must not wait for the PENDING grace period.
        self.runtime.sql(
            "UPDATE media_processing_job SET lease_until=DATE_SUB(NOW(6),INTERVAL 1 SECOND), "
            f"updated_at=NOW(6) WHERE job_id='{job['job_id']}'"
        )
        final = self.poll_succeeded(self.owner, asset_id, timeout=180)
        if final.get("processingStatus") != "SUCCEEDED":
            raise GateFailure("lease-expired retry did not complete the media job")
        if self.sql_job(asset_id)["attempts"] < 2:
            raise GateFailure("expired lease was not replaced by a new attempt")
        api = HttpApi(self.backend)
        try:
            response = api.request("POST", f"/internal/media/v1/jobs/{job['job_id']}/renew",
                headers={"X-Internal-Service-Token": self.runtime.secrets["MEDIA_INTERNAL_SERVICE_TOKEN"]},
                json={"jobVersion": 1, "leaseToken": claimed["leaseToken"]})
            if response.status_code != 409:
                raise GateFailure("stale lease was able to renew after recovery")
        finally:
            api.close()

    def broker_restart_recovery(self) -> None:
        if self.owner is None:
            raise GateFailure("owner identity was not initialized")
        self.runtime.compose("stop", "rabbitmq", timeout=90)
        self.runtime.refresh_ports()
        asset = self.upload(self.owner)
        asset_id = str(asset["assetId"])
        original = self.fetch_object(str(asset["url"]), self.fixture_sha)
        if original != self.fixture:
            raise GateFailure("original object was not readable while broker was stopped")
        response, status = self.asset_status(self.owner, asset_id)
        if response.status_code != 200 or status.get("processingStatus") not in {"PENDING", "PROCESSING"}:
            raise GateFailure("broker-stopped upload did not remain pending")
        self.runtime.compose("start", "rabbitmq", timeout=180)
        self.runtime.refresh_ports()
        time.sleep(5)
        self.runtime.wait_http(f"http://127.0.0.1:{self.runtime.ports['media-worker']}/ready", {200}, timeout=180)
        final = self.poll_succeeded(self.owner, asset_id, timeout=240)
        if final.get("processingStatus") != "SUCCEEDED":
            raise GateFailure("broker restart did not recover the pending outbox/job")

    def notification_internal(self, identity: Identity, label: str) -> dict[str, Any]:
        import httpx

        event_id = str(uuid.uuid4())
        payload = {
            "userId": identity.user_id,
            "type": "m3-runtime",
            "title": f"M3 {label}",
            "content": "runtime notification",
            "eventId": event_id,
        }
        response = httpx.post(
            f"{self.notification}/api/v1/notifications/internal",
            headers={"X-Internal-Service-Token": self.runtime.secrets["INTERNAL_SERVICE_TOKEN"]},
            json=payload,
            follow_redirects=False,
            timeout=20.0,
        )
        if response.status_code != 201:
            raise GateFailure(f"internal notification create returned {response.status_code}")
        try:
            body = response.json()
        except Exception as exc:
            raise GateFailure("internal notification create was not JSON") from exc
        if not isinstance(body, dict) or not body.get("id"):
            raise GateFailure("internal notification create omitted its id")
        return {"event_id": event_id, "notification_id": str(body["id"])}

    def read_sse_thread(self, token: str, result_queue: Queue, last_event_id: str | None = None,
                        base_url: str | None = None) -> tuple[threading.Thread, threading.Event]:
        import httpx
        stop = threading.Event()
        def run() -> None:
            headers = {"Authorization": f"Bearer {token}", "Accept": "text/event-stream"}
            if last_event_id is not None:
                headers["Last-Event-ID"] = last_event_id
            try:
                with httpx.Client(timeout=httpx.Timeout(connect=8, read=8, write=8, pool=8)) as client:
                    with client.stream("GET", f"{base_url or self.frontend}/api/v1/notifications/stream", headers=headers) as response:
                        result_queue.put(("status", response.status_code))
                        if response.status_code != 200:
                            return
                        for line in response.iter_lines():
                            result_queue.put(("line", line))
                            if stop.is_set():
                                return
            except Exception as exc:
                result_queue.put(("error", type(exc).__name__))
            finally:
                result_queue.put(("closed", None))
        thread = threading.Thread(target=run, name="m3-sse", daemon=True)
        thread.start()
        return thread, stop

    @staticmethod
    def stream_events(lines: list[str]) -> list[dict[str, Any]]:
        result = []
        frame: dict[str, str] = {}
        for line in lines:
            if not line:
                if "data" in frame:
                    result.append({"event": frame.get("event"), "id": frame.get("id"), "data": json.loads(frame["data"])})
                frame = {}
            elif not line.startswith(":") and ":" in line:
                key, value = line.split(":", 1)
                frame[key] = value.lstrip(" ")
        return result

    def notification_sse(self) -> None:
        if self.owner is None or self.other is None:
            raise GateFailure("notification identities were not initialized")
        queue: Queue = Queue()
        thread, stop = self.read_sse_thread(self.owner.token, queue)
        lines: list[str] = []
        started = time.monotonic()
        status = None
        event = None
        try:
            while time.monotonic() - started < 12:
                try:
                    kind, value = queue.get(timeout=1)
                except Empty:
                    continue
                if kind == "status":
                    status = value
                    if value != 200:
                        raise GateFailure(f"frontend SSE returned {value}")
                elif kind == "line":
                    lines.append(str(value))
                elif kind in {"closed", "error"}:
                    raise GateFailure("frontend SSE ended before the 12 second live window")
                if event is None and any(item["event"] == "reset" for item in self.stream_events(lines)):
                    event = self.notification_internal(self.owner, "stream")
        finally:
            stop.set()
            thread.join(timeout=8)
        frames = self.stream_events(lines)
        if status != 200 or event is None or not any(line.startswith(":") for line in lines):
            raise GateFailure("frontend/gateway SSE did not supply reset and heartbeats beyond 8 seconds")
        if not any(item["event"] == "notification" and item["data"].get("notificationId") == event["notification_id"] for item in frames):
            raise GateFailure("live notification frame was not received through frontend/gateway")
        api = HttpApi(self.gateway)
        try:
            response, events = api.json_value("GET", "/api/v1/notifications/events?after=0&limit=100", headers=self.auth_headers(self.owner))
            if response.status_code != 200 or not isinstance(events, list):
                raise GateFailure("owner events readback failed")
            matching = [item for item in events if str(item.get("notificationId")) == event["notification_id"]]
            if not matching:
                raise GateFailure("live event was not durably readable")
            previous = str(int(matching[-1]["cursor"]) - 1)
            response, other_events = api.json_value("GET", "/api/v1/notifications/events?after=0&limit=100", headers=self.auth_headers(self.other))
            if response.status_code != 200 or other_events:
                raise GateFailure("cross-owner events were not isolated")
        finally:
            api.close()
        queue = Queue()
        thread, stop = self.read_sse_thread(self.owner.token, queue, previous, base_url=self.gateway)
        replay: list[str] = []
        found = False
        try:
            deadline = time.monotonic() + 8
            while time.monotonic() < deadline:
                try:
                    kind, value = queue.get(timeout=1)
                except Empty:
                    continue
                if kind == "line":
                    replay.append(str(value))
                if any(item["event"] == "notification" and item["data"].get("notificationId") == event["notification_id"] for item in self.stream_events(replay)):
                    found = True
                    break
        finally:
            stop.set()
            thread.join(timeout=8)
        if not found:
            raise GateFailure("gateway Last-Event-ID did not replay the durable event")
        # Isolate expiry from the owner's replay/disconnect quota. A servlet
        # can discover a disconnected SSE peer only on its next socket write.
        expiry_identity = self.other
        token = make_jwt(expiry_identity.user_id, self.runtime.secrets["JWT_SECRET"], "MagicAlbum", 4)
        queue = Queue()
        thread, stop = self.read_sse_thread(token, queue)
        expired_lines: list[str] = []
        expired_status = None
        expired_errors: list[str] = []
        closed = False
        started = time.monotonic()
        sent_after_expiry = False
        try:
            while time.monotonic() - started < 9:
                if not sent_after_expiry and time.monotonic() - started >= 5:
                    self.notification_internal(expiry_identity, "after-expiry")
                    sent_after_expiry = True
                try:
                    kind, value = queue.get(timeout=0.5)
                except Empty:
                    continue
                if kind == "status": expired_status = value
                elif kind == "line": expired_lines.append(str(value))
                elif kind == "closed": closed = True
                elif kind == "error": expired_errors.append(str(value))
        finally:
            stop.set()
            thread.join(timeout=3)
        if expired_status != 200 or not closed or not sent_after_expiry:
            raise GateFailure("short-lived JWT did not establish and close its SSE connection "
                f"(status={expired_status}, closed={closed}, sentAfterExpiry={sent_after_expiry}, errors={expired_errors})")
        if any(item["event"] == "notification" for item in self.stream_events(expired_lines)):
            raise GateFailure("expired JWT received a notification frame")

    def browser_ui(self) -> None:
        if self.owner is None:
            raise GateFailure("owner identity was not initialized")
        try:
            from playwright.sync_api import Error as PlaywrightError
            from playwright.sync_api import sync_playwright
        except ImportError as exc:  # pragma: no cover - CI dependency guard
            raise GateFailure("Playwright Python package is required by the M3 browser gate") from exc

        encoded = base64.b64encode(self.fixture).decode()
        browser_error: list[str] = []
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True)
            context = browser.new_context()
            page = context.new_page()
            try:
                page.goto(self.frontend, wait_until="domcontentloaded", timeout=int(self.browser_timeout * 1000))
                page.evaluate(
                    """
                    ({token, user}) => {
                      localStorage.setItem('accessToken', token);
                      localStorage.setItem('currentUser', JSON.stringify(user));
                      localStorage.setItem('theme', 'light');
                    }
                    """,
                    {"token": self.owner.token, "user": self.owner.user},
                )
                page.reload(wait_until="domcontentloaded", timeout=int(self.browser_timeout * 1000))
                page.evaluate(
                    """
                    async ({token, encoded}) => {
                      const bytes = Uint8Array.from(atob(encoded), value => value.charCodeAt(0));
                      const form = new FormData();
                      form.append('file', new File([bytes], 'm3-browser.png', {type: 'image/png'}));
                      const response = await fetch('/api/v1/uploads/images', {
                        method: 'POST', headers: {Authorization: `Bearer ${token}`}, body: form
                      });
                      if (!response.ok) throw new Error(`browser upload ${response.status}`);
                      const value = await response.json();
                      if (!value.assetId || !value.url || !value.processingStatus) throw new Error('browser upload shape');
                      const user = JSON.parse(localStorage.getItem('currentUser') || '{}');
                      localStorage.setItem(`bluealbum:media:${user.id}`, JSON.stringify([value]));
                      return value;
                    }
                    """,
                    {"token": self.owner.token, "encoded": encoded},
                )
                page.reload(wait_until="domcontentloaded", timeout=int(self.browser_timeout * 1000))
                page.wait_for_selector("aside[aria-label='图片状态']", timeout=int(self.browser_timeout * 1000))
                toggle = page.locator("aside[aria-label='图片状态'] button[aria-controls='media-processing-items']")
                toggle.click()
                page.wait_for_selector("#media-processing-items", timeout=5000)
                page.get_by_text("预览已就绪", exact=True).wait_for(timeout=90000)
                viewports = [(320, 568), (390, 844), (844, 390)]
                for width, height in viewports:
                    for theme in ("light", "dark"):
                        page.set_viewport_size({"width": width, "height": height})
                        page.evaluate("theme => localStorage.setItem('theme', theme)", theme)
                        page.reload(wait_until="domcontentloaded", timeout=int(self.browser_timeout * 1000))
                        page.wait_for_selector("aside[aria-label='图片状态']", timeout=10000)
                        page.locator("aside[aria-label='图片状态'] button[aria-controls='media-processing-items']").click()
                        page.wait_for_selector("#media-processing-items", timeout=5000)
                        layout = page.evaluate(
                            """
                            () => {
                              const aside = document.querySelector('aside[aria-label="图片状态"]');
                              const box = aside?.getBoundingClientRect();
                              return {
                                width: innerWidth, height: innerHeight,
                                scrollWidth: document.documentElement.scrollWidth,
                                scrollHeight: document.documentElement.scrollHeight,
                                box: box ? {left: box.left, top: box.top, right: box.right, bottom: box.bottom} : null,
                                dark: document.documentElement.classList.contains('dark')
                              };
                            }
                            """
                        )
                        box = layout.get("box")
                        if (
                            not box
                            or layout["scrollWidth"] > width
                            or box["left"] < 0
                            or box["right"] > width
                            or box["top"] < 0
                            or box["bottom"] > height
                            or (theme == "dark" and not layout["dark"])
                        ):
                            raise GateFailure(f"media status layout overflow at {width}x{height} {theme}")
                        visible = page.locator("aside[aria-label='图片状态'] button, aside[aria-label='图片状态'] a").evaluate_all(
                            """
                            nodes => nodes.filter(node => {
                              const style = getComputedStyle(node);
                              const box = node.getBoundingClientRect();
                              return style.display !== 'none' && style.visibility !== 'hidden' && box.width > 0 && box.height > 0;
                            }).map(node => ({tag: node.tagName, height: node.getBoundingClientRect().height}))
                            """
                        )
                        if any(float(item["height"]) < 44 for item in visible):
                            raise GateFailure(f"visible media status action was below 44px at {width}x{height} {theme}")
                        page.screenshot(path=str(self.runtime.screenshot_dir / f"media-status-{width}x{height}-{theme}.png"), full_page=False)

                links = page.locator("aside[aria-label='图片状态'] a")
                link_count = links.count()
                if link_count < 2:
                    raise GateFailure("browser media status did not expose original and preview links")
                for index in range(link_count):
                    link = links.nth(index)
                    href = link.get_attribute("href") or ""
                    if not href.startswith(("http://", "https://")):
                        raise GateFailure("browser media link did not contain an actual HTTP URL")
                    with page.expect_popup(timeout=10000) as popup_info:
                        link.click()
                    popup = popup_info.value
                    popup.wait_for_load_state("commit", timeout=10000)
                    if not popup.url.startswith(("http://", "https://")):
                        raise GateFailure("browser media link popup did not navigate to an HTTP URL")
                    popup.close()
            except PlaywrightError as exc:
                browser_error.append(safe_text(exc))
            finally:
                context.close()
                browser.close()
        if browser_error:
            raise GateFailure(f"Playwright media status journey failed: {browser_error[0]}")


def write_checks(runtime: ComposeRuntime, reporter: Reporter, started_at: datetime, extra: dict[str, Any] | None = None) -> None:
    checks: dict[str, Any] = {
        "project": runtime.project,
        "startedAt": started_at.isoformat(),
        "finishedAt": now_utc().isoformat(),
        "python": ".".join(str(part) for part in PYTHON_VERSION),
        "status": "failed" if reporter.failed or runtime.cleanup_status != "passed" else "passed",
        "executed": bool(reporter.cases),
        "ports": runtime.ports,
        "browserOrigin": runtime.env_values["APP_CORS_ALLOWED_ORIGINS"],
        "cleanup": runtime.cleanup_status,
        "cases": [dataclasses.asdict(case) for case in reporter.cases],
        "expectedCases": 16,
        "plannedCoverage": [
            "isolated Compose bootstrap/build/start",
            "real owner upload, S3 fixture byte/hash and SQL readback",
            "worker stop/cancel/retry and duplicate/quarantine MQ paths",
            "broker stop/start and outbox recovery",
            "gateway/frontend notification SSE and expiry",
            "Playwright media-status viewport/theme journey",
        ],
        "uncovered": [
            "AWS IAM/ACL authorization semantics are not proven by anonymous S3Mock fixture GET",
            "Real phone, Safari, OS keyboard and cloud ACL/capacity acceptance remain separate",
        ],
    }
    if extra:
        checks.update(extra)
    runtime.checks_file.write_text(json.dumps(checks, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    reporter.write_junit(runtime.junit_file)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--artifact-dir",
        default=str(ROOT_DIR / "outputs" / "m3-runtime-ci"),
        help="parent directory for the per-project checks, JUnit, logs, and screenshots",
    )
    parser.add_argument("--browser-timeout", type=float, default=90.0)
    args = parser.parse_args(argv)

    started_at = now_utc()
    reporter = Reporter()
    artifact_parent = Path(args.artifact_dir).resolve()
    if artifact_parent == ROOT_DIR / "outputs" or not artifact_parent.is_relative_to(ROOT_DIR / "outputs"):
        print("ERROR: M3 artifacts must be stored under workspace outputs", file=sys.stderr)
        return 2
    artifact_parent.mkdir(parents=True, exist_ok=True)
    runtime: ComposeRuntime | None = None
    try:
        runtime = ComposeRuntime(artifact_parent)
        if not reporter.run("bootstrap", runtime.bootstrap):
            return 1
        if not reporter.run("build", runtime.build):
            return 1
        if not reporter.run("start", runtime.start):
            return 1
        acceptance = Acceptance(runtime, browser_timeout=args.browser_timeout)
        cases: list[tuple[str, Callable[[], None]]] = [
            ("health-and-worker-isolation", acceptance.health_and_isolation),
            ("auth-identities", acceptance.auth_identities),
            ("upload-sql-s3-readback", acceptance.happy_upload_and_readback),
            ("core-restart-durable-readback", acceptance.core_restart_readback),
            ("media-security-and-owner-isolation", acceptance.media_security),
            ("result-callback-idempotency", acceptance.result_callback_idempotency),
            ("permanent-failure-original-fallback", acceptance.permanent_failure_keeps_original),
            ("cancel-retry-versioning", acceptance.cancel_and_retry),
            ("mq-persistence-and-quarantine", acceptance.mq_persistence_and_quarantine),
            ("busy-retry-and-lease-recovery", acceptance.busy_retry_and_lease_recovery),
            ("broker-restart-outbox-recovery", acceptance.broker_restart_recovery),
            ("notification-sse-and-expiry", acceptance.notification_sse),
            ("browser-media-status-ui", acceptance.browser_ui),
        ]
        for name, action in cases:
            if not reporter.run(name, action):
                # Keep later cases from generating misleading secondary errors;
                # the first failing case and its logs remain the evidence.
                break
        return 1 if reporter.failed else 0
    except Exception as exc:  # pragma: no cover - protects mandatory artifacts
        reporter.cases.append(CaseResult("runner", "error", 0.0, safe_text(f"{type(exc).__name__}: {exc}")))
        return 1
    finally:
        if runtime is not None:
            runtime.cleanup()
            if runtime.cleanup_status != "passed":
                reporter.cases.append(CaseResult("cleanup", "failed", 0.0, "owned Compose labels were not empty after cleanup"))
            try:
                write_checks(runtime, reporter, started_at)
            except Exception as exc:  # pragma: no cover
                print(f"ERROR: unable to write M3 checks/JUnit artifacts: {safe_text(exc)}", file=sys.stderr)
                raise SystemExit(1)
            status = "failed" if reporter.failed or runtime.cleanup_status != "passed" else "passed"
            print(f"M3 runtime {status}: project={runtime.project} artifacts={runtime.artifact_dir}")
            if status != "passed":
                raise SystemExit(1)


if __name__ == "__main__":
    raise SystemExit(main())
