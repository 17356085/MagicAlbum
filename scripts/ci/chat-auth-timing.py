#!/usr/bin/env python3
"""Exercise private-message authorization timing against two real application processes."""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack, contextmanager
import json
from pathlib import Path
from queue import Empty, Queue
import re
import subprocess
from threading import Thread
import time
from urllib.parse import urlsplit
from uuid import uuid4

import httpx

from chat_cutover_support import ScenarioError, register, require, send_direct


class MysqlSession:
    def __init__(self, container: str):
        self.process = subprocess.Popen(
            [
                "docker", "exec", "-i", container, "sh", "-ec",
                'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root "$MYSQL_DATABASE" '
                "--batch --raw --skip-column-names --unbuffered",
            ],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            encoding="utf-8",
        )
        self.lines: Queue[str] = Queue()
        Thread(target=self._read_stdout, daemon=True).start()

    def _read_stdout(self):
        for line in self.process.stdout:
            self.lines.put(line.rstrip("\r\n"))

    def scalar(self, sql: str) -> str:
        try:
            self.process.stdin.write(sql + ";\n")
            self.process.stdin.flush()
            return self.lines.get(timeout=5)
        except (BrokenPipeError, Empty) as error:
            raise ScenarioError("temporary MySQL fixture did not answer a query") from error

    def execute(self, sql: str):
        marker = uuid4().hex
        require(self.scalar(sql + f"; SELECT '{marker}'") == marker, "temporary MySQL transaction did not advance")

    def close(self):
        self.process.stdin.close()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)
        finally:
            self.process.stdout.close()


def require_fixture(container: str, project: str, gateway_port: int):
    require(re.fullmatch(r"bluealbum-ci-[a-z0-9-]+", project), "expected an isolated CI project")
    require(re.fullmatch(r"[0-9a-f]{64}", container), "expected the isolated MySQL container ID")
    labels = subprocess.check_output(
        ["docker", "inspect", "--format",
         '{{ index .Config.Labels "com.docker.compose.project" }} {{ index .Config.Labels "com.docker.compose.service" }}',
         container],
        encoding="utf-8", timeout=10,
    ).strip()
    require(labels == f"{project} chat-mysql", "refusing to access a MySQL container outside this chat rehearsal")
    gateway = subprocess.check_output(
        ["docker", "ps", "-q", "--filter", f"label=com.docker.compose.project={project}",
         "--filter", "label=com.docker.compose.service=gateway"],
        encoding="utf-8", timeout=10,
    ).strip()
    require(re.fullmatch(r"[0-9a-f]{12,64}", gateway), "expected one running gateway in the same rehearsal")
    mapped_port = subprocess.check_output(
        ["docker", "port", gateway, "9000/tcp"], encoding="utf-8", timeout=10,
    ).strip()
    require(mapped_port == f"127.0.0.1:{gateway_port}", "gateway port is outside this chat rehearsal")


@contextmanager
def quota_lock(holder: MysqlSession, user_id: int):
    holder.execute("START TRANSACTION")
    try:
        used = holder.scalar(
            f"SELECT used_count FROM chat_daily_usage WHERE user_id = {user_id} "
            "ORDER BY quota_date DESC LIMIT 1 FOR UPDATE"
        )
        require(used.isdecimal(), "sender has no quota row to use as the timing barrier")
        yield
    finally:
        holder.execute("ROLLBACK")


def wait_for_quota_writer(observer: MysqlSession, connection_id: int):
    # sendDirect obtains the core decision and installs its commit check before touching this row.
    deadline = time.monotonic() + 6
    while time.monotonic() < deadline:
        waiting = observer.scalar(
            "SELECT COUNT(*) FROM performance_schema.data_lock_waits AS waits "
            "JOIN performance_schema.data_locks AS locked "
            "ON locked.ENGINE_LOCK_ID = waits.BLOCKING_ENGINE_LOCK_ID AND locked.ENGINE = waits.ENGINE "
            "JOIN performance_schema.threads AS holder ON holder.THREAD_ID = waits.BLOCKING_THREAD_ID "
            f"WHERE holder.PROCESSLIST_ID = {connection_id} "
            "AND locked.OBJECT_SCHEMA = DATABASE() AND locked.OBJECT_NAME = 'chat_daily_usage'"
        )
        if int(waiting) > 0:
            return
        time.sleep(0.025)
    raise ScenarioError("the chat write never reached the quota barrier after authorization")


def snapshot(observer: MysqlSession, sender: int) -> tuple[int, int]:
    messages, quota = observer.scalar(
        "SELECT CONCAT_WS(':',"
        f"(SELECT COUNT(*) FROM chat_messages WHERE sender_id = {sender}),"
        f"(SELECT COALESCE(SUM(used_count),0) FROM chat_daily_usage WHERE user_id = {sender}))"
    ).split(":")
    return int(messages), int(quota)


def exercise(api_url: str, container: str) -> dict:
    with ExitStack() as stack:
        holder = MysqlSession(container)
        stack.callback(holder.close)
        observer = MysqlSession(container)
        stack.callback(observer.close)
        connection_id = int(holder.scalar("SELECT CONNECTION_ID()"))
        with httpx.Client(timeout=10, trust_env=False) as transport, ThreadPoolExecutor(max_workers=1) as worker:
            marker = uuid4().hex[:10]
            a, actor = register(transport, api_url, marker, "a")
            b, peer = register(transport, api_url, marker, "b")
            sender, recipient = int(actor["id"]), int(peer["id"])
            b.request("PATCH", "/chat/settings", body={"allowRequests": True})
            first = send_direct(a, recipient, "First nonfriend message", str(uuid4()), 201)
            room = first["conversationId"]
            b.request("PUT", f"/chat/conversations/{room}/request", body={"accept": True})
            require(snapshot(observer, sender) == (1, 1), "seed did not create one message and consume one quota")

            block = f"/chat/blocks/{sender}"
            b.request("PUT", block, 204)
            send_direct(a, recipient, "Blocked before decision", str(uuid4()), 403)
            require(snapshot(observer, sender) == (1, 1), "blocked message changed storage or quota")
            b.request("DELETE", block, 204)

            operation = str(uuid4())
            allowed_body = "Already authorized when the peer blocks"
            with quota_lock(holder, sender):
                sent = worker.submit(send_direct, a, recipient, allowed_body, operation, 201)
                wait_for_quota_writer(observer, connection_id)
                blocked_at = time.monotonic()
                b.request("PUT", block, 204)
                block_elapsed_ms = round((time.monotonic() - blocked_at) * 1000)
            allowed = sent.result(timeout=10)
            require(allowed["conversationId"] == room and allowed["message"]["seq"] == first["message"]["seq"] + 1,
                    "authorized in-flight write did not advance the original conversation")
            require(snapshot(observer, sender) == (2, 2), "authorized message did not consume quota exactly once")
            send_direct(a, recipient, "Blocked after the previous decision", str(uuid4()), 403)
            replay = send_direct(a, recipient, allowed_body, operation, 200)
            require(replay["message"]["id"] == allowed["message"]["id"] and not replay["created"],
                    "retry after block did not return the existing message")
            require(snapshot(observer, sender) == (2, 2), "retry or blocked write consumed another quota")
            b.request("DELETE", block, 204)

            expired_operation = str(uuid4())
            expired_body = "Retry in a new authorization window"
            with quota_lock(holder, sender):
                expired = worker.submit(send_direct, a, recipient, expired_body, expired_operation, 503)
                wait_for_quota_writer(observer, connection_id)
                time.sleep(2.2)
            expired.result(timeout=10)
            require(snapshot(observer, sender) == (2, 2), "expired authorization left a message or quota side effect")
            retried = send_direct(a, recipient, expired_body, expired_operation, 201)
            require(retried["message"]["seq"] == allowed["message"]["seq"] + 1, "expired write left a sequence gap")
            duplicate = send_direct(a, recipient, expired_body, expired_operation, 200)
            require(duplicate["message"]["id"] == retried["message"]["id"] and not duplicate["created"],
                    "second retry did not preserve the message identifier")
            require(snapshot(observer, sender) == (3, 3), "fresh authorization and retries did not consume exactly one quota")
            return {
                "status": "passed", "blockedBeforeDecision": 403, "authorizedThenBlocked": 201,
                "newOperationAfterBlock": 403, "retryAfterBlock": 200, "expiredBeforeCommit": 503,
                "replayAfterFreshAuthorization": [201, 200], "durableMessages": 3, "quotaUsed": 3,
                "blockCallWhileWriteWasWaitingMs": block_elapsed_ms,
                "synchronization": "real split-chat MySQL quota-row lock observed in performance_schema",
            }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api-url", required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--mysql-container", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    url = urlsplit(args.api_url)
    require(url.scheme == "http" and url.hostname == "127.0.0.1" and url.path == "/api/v1" and url.port
            and not url.username and not url.query and not url.fragment, "expected the isolated loopback gateway")
    require_fixture(args.mysql_container, args.project, url.port)
    result = exercise(args.api_url, args.mysql_container)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("Split chat authorization timing and durable retries passed")


if __name__ == "__main__":
    try:
        main()
    except ScenarioError as error:
        raise SystemExit(str(error)) from error
