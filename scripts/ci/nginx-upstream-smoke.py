#!/usr/bin/env python3
"""Prove that the frontend proxy follows a Docker gateway address change.

The gate deliberately uses Docker's embedded DNS and the nginx image selected
by ``frontend/Dockerfile``.  It starts a disposable gateway fixture, checks
the real frontend configuration through a loopback-only published proxy, then
replaces the gateway while holding its old IP so a static upstream cannot
silently pass.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import secrets
import shlex
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable


ROOT_DIR = Path(__file__).resolve().parents[2]
FRONTEND_DIR = ROOT_DIR / "frontend"
NGINX_CONFIG = FRONTEND_DIR / "nginx.conf"
DOCKERFILE = FRONTEND_DIR / "Dockerfile"
DEFAULT_ARTIFACT_DIR = ROOT_DIR / "outputs" / "nginx-proxy-ci"
TRANSIENT_STATUSES = frozenset({502, 503, 504})
PROBE_PATHS = (
    "/api/v1/sections?cursor=space%20value&filter=%2Fencoded%3F&plus=a%2Bb",
    "/uploads/avatar/test%2Fobject.png?download=1&name=blue%20album%2Bcover",
    "/api/v1/notifications/stream?lastEventId=42&filter=a%2Bb",
)
LABEL_KEY = "com.bluealbum.nginx-proxy-ci.run"


class GateFailure(RuntimeError):
    """A bounded, safe-to-report integration-gate failure."""


class ProbeFailure(GateFailure):
    """An HTTP response that is not an allowed startup/recovery transient."""


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def safe_text(value: object, limit: int = 1200) -> str:
    text = str(value).replace("\x00", "")
    text = re.sub(r"(?i)bearer\s+[A-Za-z0-9._-]+", "Bearer [redacted]", text)
    text = re.sub(r"(?i)(password|secret|token|authorization)\s*[=:]\s*[^\s,;]+", r"\1=[redacted]", text)
    return text[:limit]


def parse_json(text: str, description: str) -> Any:
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        raise GateFailure(f"{description} was not valid JSON") from exc


def inspect_reports_missing(result: CommandResult) -> bool:
    """Recognize Docker's not-found result without hiding daemon failures."""

    if result.returncode == 0:
        return False
    output = f"{result.stdout}\n{result.stderr}".lower()
    return any(marker in output for marker in ("no such object", "no such container", "no such network", "not found"))


@dataclass
class CommandResult:
    args: list[str]
    returncode: int
    stdout: str
    stderr: str


class CommandRunner:
    """Run bounded commands and retain sanitized command evidence."""

    def __init__(self, log_path: Path) -> None:
        self.log_path = log_path
        self.log_path.parent.mkdir(parents=True, exist_ok=True)
        self.log_path.write_text("", encoding="utf-8")

    def run(self, args: Iterable[str], *, timeout: float, check: bool = True) -> CommandResult:
        command = [str(item) for item in args]
        started = time.monotonic()
        try:
            completed = subprocess.run(
                command,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=timeout,
                check=False,
            )
            result = CommandResult(command, completed.returncode, completed.stdout, completed.stderr)
        except subprocess.TimeoutExpired as exc:
            stdout = exc.stdout or ""
            stderr = exc.stderr or ""
            result = CommandResult(command, 124, str(stdout), f"command timed out after {timeout:g}s\n{stderr}")
        except OSError as exc:
            result = CommandResult(command, 127, "", f"command unavailable: {type(exc).__name__}: {exc}")
        elapsed = time.monotonic() - started
        entry = {
            "at": utc_now(),
            "seconds": round(elapsed, 3),
            "command": shlex.join(command),
            "returncode": result.returncode,
            "stdout": safe_text(result.stdout),
            "stderr": safe_text(result.stderr),
        }
        with self.log_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(entry, ensure_ascii=False) + "\n")
        if check and result.returncode != 0:
            detail = safe_text(result.stderr or result.stdout or "no command output")
            raise GateFailure(f"command failed ({result.returncode}): {shlex.join(command)}: {detail}")
        return result

    def docker(self, *args: str, timeout: float = 30, check: bool = True) -> CommandResult:
        return self.run(["docker", *args], timeout=timeout, check=check)


@dataclass
class ProbeResult:
    status: int | None
    body: bytes = b""
    detail: str = ""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request: urllib.request.Request, fp: Any, code: int, msg: str, headers: Any, new_url: str) -> None:
        return None


HTTP_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())


def probe(base_url: str, path: str, timeout: float = 3.0) -> ProbeResult:
    """GET one path without ambient HTTP proxy settings or redirect handling."""

    request = urllib.request.Request(base_url + path, method="GET")
    try:
        with HTTP_OPENER.open(request, timeout=timeout) as response:
            return ProbeResult(response.status, response.read(1024 * 1024))
    except urllib.error.HTTPError as exc:
        try:
            body = exc.read(1024 * 1024)
        except OSError:
            body = b""
        return ProbeResult(exc.code, body, f"HTTP {exc.code}")
    except (urllib.error.URLError, TimeoutError, socket.timeout, ConnectionError, OSError) as exc:
        return ProbeResult(None, detail=f"network wait: {type(exc).__name__}: {safe_text(exc, 300)}")


def read_runtime_image(dockerfile: Path) -> str:
    if not dockerfile.is_file():
        raise GateFailure(f"frontend Dockerfile is missing: {dockerfile}")
    from_images: list[str] = []
    for raw_line in dockerfile.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        match = re.match(r"^FROM\s+(?:(?:--platform=\S+)\s+)?([^\s]+)(?:\s+AS\s+\S+)?$", line, re.IGNORECASE)
        if match:
            from_images.append(match.group(1))
    if not from_images:
        raise GateFailure("frontend Dockerfile has no FROM image")
    image = from_images[-1]
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._/@:+-]*", image):
        raise GateFailure("frontend Dockerfile runtime image has an unsafe tag")
    return image


def validate_proxy_config(config_path: Path) -> tuple[str, str]:
    if not config_path.is_file():
        raise GateFailure(f"frontend nginx configuration is missing: {config_path}")
    source = config_path.read_text(encoding="utf-8")
    variable_proxy = re.findall(r"proxy_pass\s+http://\$gateway_upstream\s*;", source)
    if len(variable_proxy) != 3:
        raise GateFailure(f"expected exactly three variable gateway proxy_pass directives, found {len(variable_proxy)}")
    if not re.search(r"\bresolver\s+127\.0\.0\.11\s+valid=5s\s+ipv6=off\s*;", source):
        raise GateFailure("nginx configuration is missing the Docker resolver directive")
    if not re.search(r"\bresolver_timeout\s+2s\s*;", source):
        raise GateFailure("nginx configuration is missing resolver_timeout 2s")
    if not re.search(r"\bset\s+\$gateway_upstream\s+gateway:9000\s*;", source):
        raise GateFailure("nginx configuration is missing the gateway upstream variable")
    static_source, replacements = re.subn(
        r"(proxy_pass\s+)http://\$gateway_upstream(\s*;)",
        r"\1http://gateway:9000\2",
        source,
    )
    if replacements != 3 or "$gateway_upstream" in re.sub(r"\bset\s+\$gateway_upstream\s+gateway:9000\s*;", "", static_source):
        raise GateFailure("could not derive the three-directive static negative-control configuration")
    return source, static_source


def write_fixture(path: Path, phase: str) -> None:
    path.write_text(
        "server {\n"
        "    listen 9000;\n"
        "    server_name _;\n"
        "    access_log off;\n"
        "    default_type text/plain;\n"
        f'    location / {{ return 200 "{phase}|$request_uri"; }}\n'
        "}\n",
        encoding="utf-8",
    )


@dataclass
class NginxFlow:
    runner: CommandRunner
    image: str
    run_id: str
    kind: str
    proxy_config: Path
    fixture_dir: Path
    artifact: dict[str, Any]
    network: str = field(init=False)
    proxy: str = field(init=False)
    old_upstream: str = field(init=False)
    holder: str = field(init=False)
    new_upstream: str = field(init=False)
    created_containers: list[str] = field(default_factory=list)
    network_created: bool = False
    proxy_url: str | None = None
    proxy_id: str | None = None

    def __post_init__(self) -> None:
        suffix = secrets.token_hex(5)
        prefix = f"ba-nginx-{self.run_id}-{self.kind}-{suffix}"
        self.network = f"{prefix}-net"
        self.proxy = f"{prefix}-proxy"
        self.old_upstream = f"{prefix}-upstream-old"
        self.holder = f"{prefix}-old-ip-holder"
        self.new_upstream = f"{prefix}-upstream-new"
        self.artifact.update({"kind": self.kind, "network": self.network, "containers": {}})

    @property
    def label(self) -> str:
        return f"{LABEL_KEY}={self.run_id}"

    def inspect(self, resource_type: str, name: str) -> CommandResult:
        return self.runner.docker(resource_type, "inspect", name, timeout=15, check=False)

    def ensure_names_absent(self) -> None:
        for resource_type, name in [("network", self.network), *(("container", item) for item in (self.proxy, self.old_upstream, self.holder, self.new_upstream))]:
            result = self.inspect(resource_type, name)
            if result.returncode == 0:
                raise GateFailure(f"randomly selected {resource_type} already exists: {name}")
            if not inspect_reports_missing(result):
                raise GateFailure(f"could not verify that randomly selected {resource_type} is absent: {name}")

    def container_labels(self, name: str) -> dict[str, str]:
        result = self.runner.docker("container", "inspect", "--format", "{{json .Config.Labels}}", name, timeout=15)
        value = parse_json(result.stdout.strip(), f"labels for {name}")
        return value if isinstance(value, dict) else {}

    def network_labels(self) -> dict[str, str]:
        result = self.runner.docker("network", "inspect", "--format", "{{json .Labels}}", self.network, timeout=15)
        value = parse_json(result.stdout.strip(), f"labels for network {self.network}")
        return value if isinstance(value, dict) else {}

    def require_container_owned(self, name: str) -> None:
        labels = self.container_labels(name)
        if labels.get(LABEL_KEY) != self.run_id:
            raise GateFailure(f"refusing to mutate unowned container: {name}")

    def create_network(self) -> None:
        self.runner.docker("network", "create", "--driver", "bridge", "--label", self.label, self.network, timeout=30)
        self.network_created = True
        if self.network_labels().get(LABEL_KEY) != self.run_id:
            raise GateFailure(f"created network did not carry the ownership label: {self.network}")

    def _register_container(self, name: str) -> None:
        self.created_containers.append(name)
        self.artifact["containers"][name] = {"created": True}

    def container_running(self, name: str) -> bool:
        result = self.runner.docker("container", "inspect", "--format", "{{.State.Running}}", name, timeout=15, check=False)
        return result.returncode == 0 and result.stdout.strip().lower() == "true"

    def container_id(self, name: str) -> str:
        result = self.runner.docker("container", "inspect", "--format", "{{.Id}}", name, timeout=15)
        value = result.stdout.strip()
        if not re.fullmatch(r"[0-9a-f]{12,64}", value):
            raise GateFailure(f"container {name} returned an invalid id")
        return value

    def container_ip(self, name: str) -> str:
        result = self.runner.docker("container", "inspect", "--format", "{{json .NetworkSettings.Networks}}", name, timeout=15)
        networks = parse_json(result.stdout.strip(), f"network address for {name}")
        details = networks.get(self.network) if isinstance(networks, dict) else None
        address = details.get("IPAddress") if isinstance(details, dict) else None
        if not isinstance(address, str) or not re.fullmatch(r"(?:\d{1,3}\.){3}\d{1,3}", address):
            raise GateFailure(f"container {name} has no readable IPv4 address on {self.network}")
        return address

    def port_bindings(self, name: str) -> dict[str, Any]:
        result = self.runner.docker("container", "inspect", "--format", "{{json .NetworkSettings.Ports}}", name, timeout=15)
        value = parse_json(result.stdout.strip(), f"port bindings for {name}")
        return value if isinstance(value, dict) else {}

    def assert_no_published_ports(self, name: str) -> None:
        bindings = self.port_bindings(name)
        if any(value for value in bindings.values() if value):
            raise GateFailure(f"upstream container unexpectedly published a host port: {name}")

    def start_upstream(self, name: str, fixture: Path, *, alias: bool, ip: str | None = None) -> None:
        self._register_container(name)
        command = [
            "run",
            "-d",
            "--name",
            name,
            "--network",
            self.network,
            "--label",
            self.label,
        ]
        if alias:
            command.extend(["--network-alias", "gateway"])
        if ip is not None:
            command.extend(["--ip", ip])
        command.extend(["-v", f"{fixture}:/etc/nginx/conf.d/default.conf:ro", self.image])
        self.runner.docker(*command, timeout=45)
        self.assert_no_published_ports(name)
        self.require_running(name)

    def start_holder(self, old_ip: str) -> None:
        self._register_container(self.holder)
        self.runner.docker(
            "run",
            "-d",
            "--name",
            self.holder,
            "--network",
            self.network,
            "--label",
            self.label,
            "--ip",
            old_ip,
            self.image,
            "sleep",
            "90",
            timeout=45,
        )
        self.assert_no_published_ports(self.holder)
        self.require_running(self.holder)
        if self.container_ip(self.holder) != old_ip:
            raise GateFailure("old gateway IP was not held by the disposable container")

    def start_proxy(self) -> None:
        self._register_container(self.proxy)
        config_mount = f"{self.proxy_config}:/etc/nginx/conf.d/default.conf:ro"
        self.runner.docker(
            "run",
            "-d",
            "--name",
            self.proxy,
            "--network",
            self.network,
            "--label",
            self.label,
            "-p",
            "127.0.0.1::80",
            "-v",
            config_mount,
            self.image,
            timeout=45,
        )
        self.require_running(self.proxy)
        bindings = self.port_bindings(self.proxy)
        values = [item for entries in bindings.values() if entries for item in entries]
        if len(values) != 1 or values[0].get("HostIp") not in {"127.0.0.1", "127.0.0.1/32"} or not values[0].get("HostPort"):
            raise GateFailure("proxy was not published on exactly one loopback-only ephemeral port")
        port_result = self.runner.docker("port", self.proxy, "80/tcp", timeout=15)
        match = re.search(r"127\.0\.0\.1:(\d+)", port_result.stdout)
        if not match:
            raise GateFailure("proxy port readback was not loopback-only")
        port = int(match.group(1))
        if not 1 <= port <= 65535:
            raise GateFailure("proxy port readback was outside the valid range")
        if str(port) != values[0].get("HostPort"):
            raise GateFailure("proxy port readback did not match its assigned runtime binding")
        self.proxy_url = f"http://127.0.0.1:{port}"
        self.proxy_id = self.container_id(self.proxy)
        self.artifact.update({"proxyId": self.proxy_id, "proxyPort": port})

    def require_running(self, name: str) -> None:
        if not self.container_running(name):
            logs = self.runner.docker("container", "logs", name, timeout=15, check=False)
            raise GateFailure(f"container did not remain running: {name}: {safe_text(logs.stderr or logs.stdout)}")

    def require_proxy_stable(self) -> None:
        if self.proxy_id is None or not self.container_running(self.proxy):
            raise GateFailure("proxy container stopped during the address-change test")
        current_id = self.container_id(self.proxy)
        if current_id != self.proxy_id:
            raise GateFailure("proxy container identity changed during the address-change test")

    def wait_fixture(self, name: str, phase: str, timeout: float = 15.0) -> None:
        path = "/fixture-readiness?probe=ready"
        expected = f"{phase}|{path}"
        deadline = time.monotonic() + timeout
        last = "fixture did not answer"
        while time.monotonic() < deadline:
            remaining = deadline - time.monotonic()
            result = self.runner.docker(
                "exec",
                name,
                "wget",
                "-qO-",
                f"http://127.0.0.1:9000{path}",
                timeout=max(0.1, min(8.0, remaining)),
                check=False,
            )
            if result.returncode == 0 and result.stdout == expected:
                return
            last = safe_text(result.stderr or result.stdout or "fixture command failed", 400)
            if not self.container_running(name):
                raise GateFailure(f"fixture container stopped while starting: {name}: {last}")
            time.sleep(0.3)
        raise GateFailure(f"fixture did not become ready within {timeout:g}s: {name}: {last}")

    def wait_phase(self, phase: str, timeout: float) -> float:
        if self.proxy_url is None:
            raise GateFailure("proxy URL was not initialized")
        started = time.monotonic()
        deadline = started + timeout
        last_details: list[str] = []
        last_probes: list[dict[str, Any]] = []
        while time.monotonic() < deadline:
            self.require_proxy_stable()
            successes = 0
            last_details = []
            last_probes = []
            for path in PROBE_PATHS:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                result = probe(self.proxy_url, path, timeout=max(0.05, min(3.0, remaining)))
                if result.status == 200:
                    expected = f"{phase}|{path}".encode()
                    if result.body != expected:
                        raise ProbeFailure(
                            f"{self.kind} {phase} URI mismatch for {path}: got {safe_text(result.body, 300)}"
                        )
                    successes += 1
                    last_probes.append({"path": path, "status": 200, "body": result.body.decode("utf-8", "replace")})
                elif result.status in TRANSIENT_STATUSES or result.status is None:
                    last_details.append(f"{path}: {result.detail or result.status}")
                    last_probes.append({"path": path, "status": result.status or "network", "detail": result.detail})
                else:
                    raise ProbeFailure(f"{self.kind} {phase} returned unexpected HTTP status {result.status} for {path}")
            if successes == len(PROBE_PATHS):
                elapsed = time.monotonic() - started
                if elapsed > timeout:
                    raise GateFailure(f"{self.kind} {phase} exceeded its {timeout:g}s recovery window")
                self.artifact.setdefault("phaseResults", {})[phase] = {
                    "status": "passed",
                    "seconds": round(elapsed, 3),
                    "probes": last_probes,
                }
                return elapsed
            time.sleep(min(0.4, max(0.05, deadline - time.monotonic())))
        self.artifact.setdefault("phaseResults", {})[phase] = {
            "status": "failed",
            "seconds": round(time.monotonic() - started, 3),
            "probes": last_probes,
        }
        raise GateFailure(f"{self.kind} {phase} did not recover within {timeout:g}s: {'; '.join(last_details)}")

    def wait_static_failure(self, phase: str, timeout: float) -> float:
        if self.proxy_url is None:
            raise GateFailure("proxy URL was not initialized")
        started = time.monotonic()
        deadline = started + timeout
        saw_http_transient = False
        observations = 0
        last_probes: list[dict[str, Any]] = []
        while time.monotonic() < deadline:
            self.require_proxy_stable()
            last_probes = []
            for path in PROBE_PATHS:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                result = probe(self.proxy_url, path, timeout=max(0.05, min(3.0, remaining)))
                observations += 1
                if result.status == 200:
                    expected = f"{phase}|{path}".encode()
                    if result.body == expected:
                        raise GateFailure(f"static control unexpectedly recovered {path} after the gateway address changed")
                    raise ProbeFailure(f"static control returned a wrong 200 body for {path}")
                if result.status in TRANSIENT_STATUSES:
                    saw_http_transient = True
                    last_probes.append({"path": path, "status": result.status, "detail": result.detail})
                    continue
                if result.status is None:
                    last_probes.append({"path": path, "status": "network", "detail": result.detail})
                    continue
                raise ProbeFailure(f"static control returned unexpected HTTP status {result.status} for {path}")
            time.sleep(min(0.4, max(0.05, deadline - time.monotonic())))
        if not saw_http_transient or observations == 0:
            self.artifact.setdefault("phaseResults", {})[phase] = {
                "status": "failed",
                "seconds": round(time.monotonic() - started, 3),
                "probes": last_probes,
            }
            raise GateFailure("static control did not produce an HTTP gateway failure; startup evidence is insufficient")
        elapsed = time.monotonic() - started
        self.artifact.setdefault("phaseResults", {})[phase] = {
            "status": "expected-failure",
            "seconds": round(elapsed, 3),
            "httpTransientObserved": saw_http_transient,
            "probes": last_probes,
        }
        return elapsed

    def remove_owned_container(self, name: str) -> None:
        self.require_container_owned(name)
        self.runner.docker("container", "rm", "--force", name, timeout=30)

    def run(self, *, static_control: bool) -> None:
        self.ensure_names_absent()
        self.create_network()
        phase1_fixture = self.fixture_dir / "phase1.conf"
        phase2_fixture = self.fixture_dir / "phase2.conf"
        self.start_upstream(self.old_upstream, phase1_fixture, alias=True)
        self.wait_fixture(self.old_upstream, "phase1")
        self.start_proxy()
        self.wait_phase("phase1", timeout=20.0)

        old_ip = self.container_ip(self.old_upstream)
        self.remove_owned_container(self.old_upstream)
        self.start_holder(old_ip)
        self.start_upstream(self.new_upstream, phase2_fixture, alias=True)
        self.wait_fixture(self.new_upstream, "phase2")
        new_ip = self.container_ip(self.new_upstream)
        if new_ip == old_ip:
            raise GateFailure(f"gateway address did not change ({old_ip}); old IP was not successfully occupied")
        self.artifact.update({"oldGatewayIp": old_ip, "newGatewayIp": new_ip, "gatewayIpChanged": True})
        if self.proxy_id != self.container_id(self.proxy):
            raise GateFailure("proxy container identity changed before recovery probing")
        if static_control:
            self.wait_static_failure("phase2", timeout=8.0)
            self.artifact["staticControlRejected"] = True
        else:
            self.wait_phase("phase2", timeout=20.0)
            self.artifact["proxyIdStable"] = self.proxy_id == self.container_id(self.proxy)
            self.artifact["recoveredWithin20s"] = True

    def cleanup(self) -> list[str]:
        errors: list[str] = []
        for name in reversed(self.created_containers):
            result = self.inspect("container", name)
            if inspect_reports_missing(result):
                continue
            if result.returncode != 0:
                errors.append(f"could not inspect owned container during cleanup: {name}: {safe_text(result.stderr or result.stdout)}")
                continue
            try:
                labels = self.container_labels(name)
            except GateFailure as exc:
                errors.append(str(exc))
                continue
            if labels.get(LABEL_KEY) != self.run_id:
                errors.append(f"refusing to remove unowned container during cleanup: {name}")
                continue
            removed = self.runner.docker("container", "rm", "--force", name, timeout=30, check=False)
            if removed.returncode != 0:
                errors.append(f"could not remove owned container {name}: {safe_text(removed.stderr or removed.stdout)}")
                continue
            remaining = self.inspect("container", name)
            if remaining.returncode == 0:
                errors.append(f"owned container remained after cleanup: {name}")
            elif not inspect_reports_missing(remaining):
                errors.append(f"could not verify container cleanup: {name}: {safe_text(remaining.stderr or remaining.stdout)}")
        network_result = self.inspect("network", self.network)
        if inspect_reports_missing(network_result):
            self.network_created = False
        elif network_result.returncode != 0:
            errors.append(f"could not inspect owned network during cleanup: {self.network}: {safe_text(network_result.stderr or network_result.stdout)}")
        else:
            try:
                labels = self.network_labels()
            except GateFailure as exc:
                errors.append(str(exc))
                labels = {}
            if labels.get(LABEL_KEY) != self.run_id:
                errors.append(f"refusing to remove unowned network during cleanup: {self.network}")
            else:
                removed = self.runner.docker("network", "rm", self.network, timeout=30, check=False)
                if removed.returncode != 0:
                    errors.append(f"could not remove owned network {self.network}: {safe_text(removed.stderr or removed.stdout)}")
                else:
                    remaining = self.inspect("network", self.network)
                    if remaining.returncode == 0:
                        errors.append(f"owned network remained after cleanup: {self.network}")
                    elif not inspect_reports_missing(remaining):
                        errors.append(f"could not verify network cleanup: {self.network}: {safe_text(remaining.stderr or remaining.stdout)}")
        self.artifact["cleanup"] = {"status": "passed" if not errors else "failed", "errors": errors}
        return errors


def ensure_image(runner: CommandRunner, image: str) -> None:
    inspected = runner.docker("image", "inspect", image, timeout=30, check=False)
    if inspected.returncode == 0:
        return
    failures: list[str] = []
    for attempt in range(1, 4):
        result = runner.docker("pull", image, timeout=90, check=False)
        if result.returncode == 0:
            return
        failures.append(f"attempt {attempt}: {safe_text(result.stderr or result.stdout)}")
        if attempt < 3:
            time.sleep(2 * attempt)
    raise GateFailure(f"could not pull runtime image after three preparation attempts: {' | '.join(failures)}")


def verify_owned_inventory(runner: CommandRunner, run_id: str) -> list[str]:
    """Use Docker's label inventory as the final leak check for this run."""

    errors: list[str] = []
    filter_value = f"label={LABEL_KEY}={run_id}"
    for resource_type, command in (
        ("container", ("container", "ls", "-aq", "--filter", filter_value)),
        ("network", ("network", "ls", "-q", "--filter", filter_value)),
    ):
        result = runner.docker(*command, timeout=15, check=False)
        if result.returncode != 0:
            errors.append(f"could not verify final {resource_type} inventory: {safe_text(result.stderr or result.stdout)}")
            continue
        remaining = [line.strip() for line in result.stdout.splitlines() if line.strip()]
        if remaining:
            errors.append(f"owned {resource_type} resources remained after cleanup: {', '.join(remaining)}")
    return errors


def artifact_path(value: str) -> Path:
    candidate = Path(value).resolve()
    outputs_root = (ROOT_DIR / "outputs").resolve()
    if candidate == outputs_root or not candidate.is_relative_to(outputs_root):
        raise GateFailure(f"artifacts must be stored below {outputs_root}")
    return candidate


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact-dir", default=str(DEFAULT_ARTIFACT_DIR))
    args = parser.parse_args(argv)
    started_at = utc_now()
    artifact_dir: Path | None = None
    runner: CommandRunner | None = None
    dynamic: NginxFlow | None = None
    static: NginxFlow | None = None
    temporary_dir: tempfile.TemporaryDirectory[str] | None = None
    run_id: str | None = None
    image = ""
    failure: str | None = None
    static_control_rejected = False
    cleanup_errors: list[str] = []
    report: dict[str, Any] = {
        "status": "failed",
        "startedAt": started_at,
        "finishedAt": None,
        "image": None,
        "config": "frontend/nginx.conf",
        "dockerfile": "frontend/Dockerfile",
        "dynamic": {},
        "staticControl": {},
        "staticControlRejected": False,
        "cleanup": {"status": "not-run", "errors": []},
        "failure": None,
    }
    try:
        artifact_dir = artifact_path(args.artifact_dir)
        artifact_dir.mkdir(parents=True, exist_ok=True)
        runner = CommandRunner(artifact_dir / "commands.jsonl")
        source, static_source = validate_proxy_config(NGINX_CONFIG)
        image = read_runtime_image(DOCKERFILE)
        report["image"] = image
        report["configSha256"] = hashlib.sha256(source.encode()).hexdigest()
        runner.docker("info", timeout=30)
        ensure_image(runner, image)
        temporary_dir = tempfile.TemporaryDirectory(prefix="bluealbum-nginx-", dir=str(artifact_dir))
        fixture_dir = Path(temporary_dir.name)
        write_fixture(fixture_dir / "phase1.conf", "phase1")
        write_fixture(fixture_dir / "phase2.conf", "phase2")
        static_config = fixture_dir / "static-nginx.conf"
        static_config.write_text(static_source, encoding="utf-8")
        run_id = secrets.token_hex(8)
        report["runId"] = run_id
        dynamic = NginxFlow(runner, image, run_id, "dynamic", NGINX_CONFIG, fixture_dir, report["dynamic"])
        dynamic.run(static_control=False)
        static = NginxFlow(runner, image, run_id, "static", static_config, fixture_dir, report["staticControl"])
        static.run(static_control=True)
        static_control_rejected = bool(report["staticControl"].get("staticControlRejected"))
        if not static_control_rejected:
            raise GateFailure("static negative control did not reject the old literal upstream")
    except Exception as exc:  # the report is the failure contract for CI
        failure = safe_text(f"{type(exc).__name__}: {exc}")
    finally:
        for flow in (static, dynamic):
            if flow is not None:
                try:
                    cleanup_errors.extend(flow.cleanup())
                except Exception as exc:  # preserve the failure report if the daemon disappears during cleanup
                    cleanup_errors.append(f"cleanup exception for {flow.kind}: {safe_text(exc)}")
        if temporary_dir is not None:
            try:
                temporary_dir.cleanup()
            except Exception as exc:
                cleanup_errors.append(f"fixture cleanup failed: {safe_text(exc)}")
                report["fixtureDirectoryRetained"] = temporary_dir.name
        inventory_errors: list[str] = []
        if runner is not None and run_id is not None:
            inventory_errors = verify_owned_inventory(runner, run_id)
            cleanup_errors.extend(inventory_errors)
        report["finalInventory"] = {
            "status": "passed" if not inventory_errors else "failed",
            "errors": inventory_errors,
        }
        report["finishedAt"] = utc_now()
        report["staticControlRejected"] = static_control_rejected
        if cleanup_errors:
            report["failure"] = "; ".join(cleanup_errors) if failure is None else f"{failure}; {'; '.join(cleanup_errors)}"
        else:
            report["failure"] = failure
        report["cleanup"] = {
            "status": "passed" if not cleanup_errors else "failed",
            "errors": cleanup_errors,
        }
        if failure is None and not cleanup_errors and static_control_rejected:
            report["status"] = "passed"
        if artifact_dir is not None:
            (artifact_dir / "addresses.json").write_text(
                json.dumps(
                    {
                        "dynamic": report.get("dynamic", {}),
                        "staticControl": report.get("staticControl", {}),
                    },
                    ensure_ascii=False,
                    indent=2,
                )
                + "\n",
                encoding="utf-8",
            )
            (artifact_dir / "checks.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if report["status"] == "passed":
        print(f"nginx upstream recovery passed: {artifact_dir}")
        return 0
    print(f"nginx upstream recovery failed: {artifact_dir}: {report.get('failure')}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
