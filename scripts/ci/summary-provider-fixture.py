#!/usr/bin/env python3
"""A deterministic, local-only OpenAI-compatible provider fixture.

The M4B runtime gate runs this file in a disposable ``python:3.13-alpine``
container.  The fixture deliberately records only bounded protocol evidence:
the request hash, input character count, a short tail marker and a call
number.  It never stores request bodies or authorization values.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any


MODES = frozenset(
    {
        "normal",
        "delay",
        "rate-limit-first",
        "upstream",
        "auth",
        "invalid",
        "no-usage",
        "empty",
        "oversize",
    }
)
MAX_REQUEST_BYTES = 256 * 1024
DELAY_SECONDS = 10.0
TAIL_MARKER_CHARS = 16


class FixtureState:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._mode = "normal"
        self._calls = 0
        self._records: list[dict[str, Any]] = []

    def reset(self) -> dict[str, Any]:
        with self._lock:
            self._calls = 0
            self._records = []
            return self.snapshot_locked()

    def set_mode(self, mode: str) -> dict[str, Any]:
        normalized = str(mode or "").strip().lower()
        if normalized not in MODES:
            raise ValueError("unsupported fixture mode")
        with self._lock:
            self._mode = normalized
            return self.snapshot_locked()

    def begin_call(self, content: str) -> tuple[str, int, dict[str, Any]]:
        digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
        with self._lock:
            self._calls += 1
            call_number = self._calls
            mode = self._mode
            record = {
                "inputSha256": digest,
                "inputChars": len(content),
                "tailMarker": content[-TAIL_MARKER_CHARS:],
                "callNumber": call_number,
            }
            self._records.append(record)
            return mode, call_number, record

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            return self.snapshot_locked()

    def snapshot_locked(self) -> dict[str, Any]:
        return {
            "mode": self._mode,
            "calls": self._calls,
            "records": [dict(item) for item in self._records],
        }


class FixtureHandler(BaseHTTPRequestHandler):
    server: "FixtureServer"

    def log_message(self, _format: str, *_args: object) -> None:
        # Request paths can contain untrusted values.  Keep the fixture quiet
        # so logs cannot accidentally become a body or credential sink.
        return

    def _write_json(self, status: int, value: object, headers: dict[str, str] | None = None) -> None:
        payload = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        for key, item in (headers or {}).items():
            self.send_header(key, item)
        self.end_headers()
        try:
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _read_json(self) -> dict[str, Any]:
        raw_length = self.headers.get("Content-Length", "")
        try:
            length = int(raw_length)
        except ValueError as exc:
            raise ValueError("invalid content length") from exc
        if length < 0 or length > MAX_REQUEST_BYTES:
            raise ValueError("request body too large")
        body = self.rfile.read(length)
        if len(body) != length:
            raise ValueError("truncated request body")
        value = json.loads(body.decode("utf-8"))
        if not isinstance(value, dict):
            raise ValueError("request body must be an object")
        return value

    def do_GET(self) -> None:  # noqa: N802 - stdlib handler API
        if self.path == "/health":
            self._write_json(HTTPStatus.OK, {"status": "ok", "protocol": "controlled-summary"})
            return
        if self.path == "/control/state":
            self._write_json(HTTPStatus.OK, self.server.state.snapshot())
            return
        self._write_json(HTTPStatus.NOT_FOUND, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802 - stdlib handler API
        if self.path == "/control/reset":
            self._write_json(HTTPStatus.OK, self.server.state.reset())
            return
        if self.path == "/control/mode":
            try:
                value = self._read_json()
                snapshot = self.server.state.set_mode(str(value.get("mode", "")))
            except (ValueError, json.JSONDecodeError) as exc:
                self._write_json(HTTPStatus.BAD_REQUEST, {"error": str(exc)[:120]})
                return
            self._write_json(HTTPStatus.OK, snapshot)
            return
        if self.path == "/v1/chat/completions":
            self._completion()
            return
        self._write_json(HTTPStatus.NOT_FOUND, {"error": "not found"})

    def _completion(self) -> None:
        try:
            request = self._read_json()
            messages = request.get("messages")
            if not isinstance(messages, list):
                raise ValueError("messages must be an array")
            user_content = ""
            for message in messages:
                if isinstance(message, dict) and message.get("role") == "user" and isinstance(message.get("content"), str):
                    user_content = message["content"]
            mode, call_number, record = self.server.state.begin_call(user_content)
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            self._write_json(HTTPStatus.BAD_REQUEST, {"error": str(exc)[:120]})
            return

        request_id = f"fixture-request-{call_number}"
        headers = {"x-request-id": request_id}
        if mode == "delay":
            time.sleep(DELAY_SECONDS)
        if mode == "rate-limit-first" and call_number == 1:
            self._write_json(HTTPStatus.TOO_MANY_REQUESTS, {"error": {"message": "controlled rate limit"}}, headers)
            return
        if mode == "upstream":
            self._write_json(HTTPStatus.BAD_GATEWAY, {"error": {"message": "controlled upstream"}}, headers)
            return
        if mode == "auth":
            self._write_json(HTTPStatus.UNAUTHORIZED, {"error": {"message": "controlled auth"}}, headers)
            return
        if mode == "invalid":
            self._write_json(HTTPStatus.OK, {"id": f"fixture-response-{call_number}", "choices": []}, headers)
            return
        if mode == "empty":
            body: dict[str, Any] = {
                "id": f"fixture-response-{call_number}",
                "model": "controlled-summary-response",
                "choices": [{"message": {"content": ""}}],
            }
            self._write_json(HTTPStatus.OK, body, headers)
            return
        if mode == "oversize":
            huge = "x" * (128 * 1024 + 4096)
            self._write_json(
                HTTPStatus.OK,
                {"id": f"fixture-response-{call_number}", "model": "controlled-summary-response", "choices": [{"message": {"content": huge}}]},
                headers,
            )
            return

        summary = f"controlled summary input-sha256={record['inputSha256']}"
        body = {
            "id": f"fixture-response-{call_number}",
            "model": "controlled-summary-response",
            "choices": [{"message": {"content": summary}}],
        }
        if mode != "no-usage":
            body["usage"] = {
                "prompt_tokens": max(1, len(user_content.encode("utf-8")) // 4),
                "completion_tokens": 7,
            }
        self._write_json(HTTPStatus.OK, body, headers)


class FixtureServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], state: FixtureState) -> None:
        self.state = state
        super().__init__(address, FixtureHandler)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8088)
    args = parser.parse_args(argv)
    if not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535")
    server = FixtureServer((args.host, args.port), FixtureState())
    try:
        server.serve_forever(poll_interval=0.2)
    except KeyboardInterrupt:
        return 0
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
