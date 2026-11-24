#!/usr/bin/env python3
"""Verify two realtime nodes against the same disposable split chat stack."""

import argparse
from contextlib import ExitStack
import json
from pathlib import Path
import sys
import time
from urllib.parse import urlsplit
from uuid import uuid4

import httpx
import websocket


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def check_endpoint(value, scheme, path):
    url = urlsplit(value)
    require(
        url.scheme == scheme and url.hostname == "127.0.0.1" and url.port and url.path == path
        and url.username is None and not (url.query or url.fragment),
        "expected a disposable loopback endpoint",
    )


def receive(connection, timeout, expected):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        connection.settimeout(max(0.01, deadline - time.monotonic()))
        try:
            frame = connection.recv()
        except websocket.WebSocketTimeoutException:
            break
        except websocket.WebSocketConnectionClosedException:
            frame = ""
        if frame == "heartbeat":
            continue
        require(frame == expected, "realtime node returned an unexpected frame or disconnected")
        return
    require(expected is None, "realtime node did not return the expected frame")


def connect(resources, node, origin, token):
    connection = websocket.create_connection(node, timeout=8, origin=origin, http_no_proxy=["127.0.0.1"])
    resources.callback(connection.shutdown)
    connection.send(token)
    receive(connection, 8, "ready")
    return connection


def transition(directory, request, completed):
    (directory / request).touch(exist_ok=False)
    result = directory / completed
    deadline = time.monotonic() + 100
    while time.monotonic() < deadline:
        if result.is_file():
            return result.read_text(encoding="ascii").strip()
        time.sleep(0.1)
    raise RuntimeError(f"container operation did not complete ({completed})")


def exercise(args, state, resources):
    sender, recipient, other = (state["accounts"][role] for role in ("a", "b", "c"))
    require(len({sender["id"], recipient["id"], other["id"]}) == 3, "expected three distinct fixture accounts")
    room = state["direct"]
    client = resources.enter_context(httpx.Client(base_url=args.api_url, trust_env=False, timeout=10))

    def request(account, method, path, status, **kwargs):
        response = client.request(method, path, headers={"Authorization": f"Bearer {account['token']}"}, **kwargs)
        require(response.status_code == status, f"chat {method} expected HTTP {status}, got {response.status_code}")
        return response.json()

    def send(previous):
        body = {"content": f"realtime check {uuid4().hex}", "clientMessageId": str(uuid4())}
        result = request(sender, "POST", f"chat/direct/{recipient['id']}/messages", 201, json=body)
        message = result["message"]
        require(result["conversationId"] == room and message["seq"] == previous + 1
                and message["content"] == body["content"], "chat writer returned incorrect message data")
        return message

    first_node = connect(resources, args.node_a, args.frontend_url, recipient["token"])
    second_node = connect(resources, args.node_b, args.frontend_url, recipient["token"])
    unrelated = connect(resources, args.node_b, args.frontend_url, other["token"])

    initial = send(state["first"]["seq"])
    receive(first_node, 8, "changed")
    receive(second_node, 8, "changed")
    receive(unrelated, 1, None)

    transition(args.sync_dir, "stop-requested", "stopped")
    receive(first_node, 8, "")
    missed = send(initial["seq"])
    receive(second_node, 8, "changed")
    receive(unrelated, 1, None)

    restarted_address = transition(args.sync_dir, "start-requested", "started")
    check_endpoint(restarted_address, "ws", "/api/v1/chat/realtime")
    require(restarted_address != args.node_b, "restarted node shares the surviving node address")
    reconnected = connect(resources, restarted_address, args.frontend_url, recipient["token"])
    backlog = request(recipient, "GET", f"chat/conversations/{room}/messages", 200, params={"afterSeq": initial["seq"]})
    require(not backlog["hasMore"] and len(backlog["items"]) == 1, "reconnect returned unexpected backlog size")
    recovered = backlog["items"][0]
    require(all(recovered[key] == missed[key] for key in ("id", "seq", "content")), "reconnect missed the offline message")

    send(missed["seq"])
    receive(reconnected, 8, "changed")
    receive(second_node, 8, "changed")
    receive(unrelated, 1, None)
    return {"authenticated_nodes": 2, "writes": 3, "fanout_before_restart": 2, "fanout_after_restart": 2,
            "surviving_connection_deliveries": 1, "offline_messages_recovered": 1, "unrelated_signals": 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api-url", required=True)
    parser.add_argument("--frontend-url", required=True)
    parser.add_argument("--node-a", required=True)
    parser.add_argument("--node-b", required=True)
    parser.add_argument("--state", required=True, type=Path)
    parser.add_argument("--sync-dir", required=True, type=Path)
    args = parser.parse_args()
    try:
        check_endpoint(args.api_url, "http", "/api/v1")
        check_endpoint(args.frontend_url, "http", "")
        for node in (args.node_a, args.node_b):
            check_endpoint(node, "ws", "/api/v1/chat/realtime")
        require(args.node_a != args.node_b, "expected two distinct realtime addresses")
        require(args.sync_dir.is_dir() and not any(args.sync_dir.iterdir()), "expected an empty coordination directory")
        state = json.loads(args.state.read_text(encoding="utf-8"))
        require(state["phase"] == "seed", "expected a fresh split chat fixture")
        with ExitStack() as resources:
            evidence = exercise(args, state, resources)
    except RuntimeError as error:
        print(f"chat realtime check failed: {error}", file=sys.stderr)
        return 1
    except (httpx.HTTPError, websocket.WebSocketException, ValueError, KeyError, OSError) as error:
        print(f"chat realtime check failed: {type(error).__name__}", file=sys.stderr)
        return 1
    print(json.dumps(evidence, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
