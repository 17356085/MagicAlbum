#!/usr/bin/env python3
"""Measure the split Chat REST history-polling baseline."""

from __future__ import annotations

import argparse
import asyncio
import json
import math
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

import httpx


CLIENT_COUNT = 50
MESSAGE_COUNT = 20
POLL_INTERVAL_SECONDS = 3.0
SEND_INTERVAL_SECONDS = 0.5
OVERALL_TIMEOUT_SECONDS = 60.0
CLOSE_TIMEOUT_SECONDS = 5.0


class LoadError(RuntimeError):
    """A sanitized, user-facing load-test failure."""


@dataclass(frozen=True)
class Fixture:
    writer_token: str
    recipient_token: str
    group_id: int
    baseline_seq: int


def require(condition: bool, message: str) -> None:
    if not condition:
        raise LoadError(message)


def _positive_int(value: object, description: str) -> int:
    require(isinstance(value, int) and not isinstance(value, bool) and value > 0, description)
    return value


def validate_api_url(value: str) -> str:
    try:
        endpoint = urlsplit(value)
        port = endpoint.port
    except ValueError as error:
        raise LoadError("invalid loopback API URL") from error
    require(
        endpoint.scheme == "http"
        and endpoint.hostname == "127.0.0.1"
        and port is not None
        and 1 <= port <= 65535
        and endpoint.path == "/api/v1"
        and not endpoint.username
        and not endpoint.password
        and not endpoint.query
        and not endpoint.fragment,
        "expected the exact disposable loopback URL http://127.0.0.1:<port>/api/v1",
    )
    return value


def load_fixture(path: Path) -> Fixture:
    try:
        state = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise LoadError(f"could not read fixture state ({type(error).__name__})") from error
    require(isinstance(state, dict), "fixture state must be a JSON object")
    require(state.get("phase") in {"split", "recover"}, "fixture state must be from split or recover")

    accounts = state.get("accounts")
    require(isinstance(accounts, dict), "fixture state has no accounts")
    writer = accounts.get("a")
    recipient = accounts.get("b")
    require(isinstance(writer, dict) and isinstance(recipient, dict), "fixture state is missing chat accounts")
    writer_token = writer.get("token")
    recipient_token = recipient.get("token")
    require(isinstance(writer_token, str) and bool(writer_token), "fixture state has no writer token")
    require(isinstance(recipient_token, str) and bool(recipient_token), "fixture state has no recipient token")

    group_id = _positive_int(state.get("group"), "fixture state has no group")
    next_group = state.get("next_group")
    require(isinstance(next_group, dict), "fixture state has no next group message")
    baseline_seq = _positive_int(next_group.get("seq"), "fixture state has no next group sequence")
    return Fixture(writer_token, recipient_token, group_id, baseline_seq)


def _timeout() -> httpx.Timeout:
    return httpx.Timeout(connect=5.0, read=10.0, write=5.0, pool=5.0)


def _client(token: str) -> httpx.AsyncClient:
    return httpx.AsyncClient(
        headers={
            "Authorization": f"Bearer {token}",
            "X-Device-Id": str(uuid.uuid4()),
        },
        follow_redirects=False,
        limits=httpx.Limits(max_connections=1, max_keepalive_connections=1),
        timeout=_timeout(),
        trust_env=False,
    )


async def request_json(
    client: httpx.AsyncClient,
    method: str,
    url: str,
    label: str,
    *,
    expected_status: int,
    params: dict[str, int] | None = None,
    body: dict[str, str] | None = None,
) -> object:
    try:
        response = await client.request(method, url, params=params, json=body)
    except httpx.HTTPError as error:
        raise LoadError(f"{label} request failed ({type(error).__name__})") from error
    if 300 <= response.status_code < 400:
        raise LoadError(f"{label} returned a redirect")
    require(response.status_code == expected_status, f"{label} returned HTTP {response.status_code}")
    try:
        return response.json()
    except ValueError as error:
        raise LoadError(f"{label} returned invalid JSON") from error


def _message_fields(payload: object, label: str) -> tuple[int, int, str]:
    require(isinstance(payload, dict), f"{label} returned an invalid message")
    message_id = _positive_int(payload.get("id"), f"{label} returned an invalid message id")
    sequence = _positive_int(payload.get("seq"), f"{label} returned an invalid message sequence")
    content = payload.get("content")
    require(isinstance(content, str), f"{label} returned an invalid message body")
    return message_id, sequence, content


def _history_items(payload: object, label: str) -> list[dict[str, object]]:
    require(isinstance(payload, dict), f"{label} returned an invalid history")
    items = payload.get("items")
    require(isinstance(items, list), f"{label} returned invalid history items")
    require(isinstance(payload.get("hasMore"), bool), f"{label} returned invalid history paging")
    rows: list[dict[str, object]] = []
    for item in items:
        require(isinstance(item, dict), f"{label} returned an invalid history row")
        rows.append(item)
    return rows


async def _sleep_until(target: float) -> None:
    delay = target - time.monotonic()
    if delay > 0:
        await asyncio.sleep(delay)


async def warm_reader(client: httpx.AsyncClient, api_url: str, fixture: Fixture, ready_at: float) -> None:
    await _sleep_until(ready_at)
    payload = await request_json(
        client, "GET", f"{api_url}/chat/conversations/{fixture.group_id}/messages", "poller warm-up",
        expected_status=200, params={"afterSeq": fixture.baseline_seq - 1},
    )
    require(any(row.get("seq") == fixture.baseline_seq for row in _history_items(payload, "poller warm-up")),
            "poller warm-up could not read the migrated history cursor")


async def poll_client(
    index: int,
    client: httpx.AsyncClient,
    fixture: Fixture,
    expected: dict[int, dict[str, object]],
    seen_by_client: list[set[int]],
    observations: dict[tuple[int, int], tuple[int, float]],
    state_lock: asyncio.Lock,
    start_at: float,
    deadline: float,
    api_url: str,
) -> None:
    cursor = fixture.baseline_seq
    next_poll = start_at + (index * POLL_INTERVAL_SECONDS / CLIENT_COUNT)
    await _sleep_until(next_poll)
    history_url = f"{api_url}/chat/conversations/{fixture.group_id}/messages"

    while len(seen_by_client[index]) < MESSAGE_COUNT:
        if time.monotonic() >= deadline:
            raise LoadError(f"client {index + 1} did not observe all messages before the deadline")
        payload = await request_json(
            client,
            "GET",
            history_url,
            f"client {index + 1} history poll",
            expected_status=200,
            params={"afterSeq": cursor},
        )
        observed_at = time.monotonic()
        rows = _history_items(payload, f"client {index + 1} history poll")
        highest = cursor
        for row in rows:
            sequence = _positive_int(row.get("seq"), f"client {index + 1} returned an invalid sequence")
            require(sequence > cursor, f"client {index + 1} history repeated an old sequence")
            expected_message = expected.get(sequence)
            require(expected_message is not None, f"client {index + 1} observed an unexpected sequence")
            message_id = _positive_int(row.get("id"), f"client {index + 1} returned an invalid message id")
            require(
                row.get("content") == expected_message["content"],
                f"client {index + 1} observed an unexpected message",
            )
            async with state_lock:
                require(sequence not in seen_by_client[index], f"client {index + 1} observed a duplicate message")
                known_id = expected_message.get("id")
                if known_id is not None:
                    require(message_id == known_id, f"client {index + 1} observed a mismatched message id")
                seen_by_client[index].add(sequence)
                observations[(index, sequence)] = (message_id, observed_at)
            highest = max(highest, sequence)
        cursor = highest
        next_poll += POLL_INTERVAL_SECONDS
        await _sleep_until(next_poll)


async def writer(
    client: httpx.AsyncClient,
    fixture: Fixture,
    expected: dict[int, dict[str, object]],
    submit_latencies: list[float],
    observations: dict[tuple[int, int], tuple[int, float]],
    state_lock: asyncio.Lock,
    start_at: float,
    deadline: float,
    api_url: str,
) -> None:
    send_url = f"{api_url}/chat/groups/{fixture.group_id}/messages"
    next_send = start_at
    for offset in range(MESSAGE_COUNT):
        await _sleep_until(next_send)
        if time.monotonic() >= deadline:
            raise LoadError("writer did not finish before the deadline")
        sequence = fixture.baseline_seq + offset + 1
        expected_message = expected[sequence]
        started_at = time.monotonic()
        payload = await request_json(
            client,
            "POST",
            send_url,
            f"writer message {offset + 1}",
            expected_status=200,
            body={
                "clientMessageId": expected_message["client_message_id"],
                "content": expected_message["content"],
            },
        )
        finished_at = time.monotonic()
        message_id, actual_sequence, content = _message_fields(payload, f"writer message {offset + 1}")
        require(actual_sequence == sequence, f"writer message {offset + 1} returned a non-contiguous sequence")
        require(content == expected_message["content"], f"writer message {offset + 1} returned an unexpected message")
        async with state_lock:
            expected_message["id"] = message_id
            expected_message["submitted_at"] = started_at
            for client_index in range(CLIENT_COUNT):
                observed = observations.get((client_index, sequence))
                if observed is not None:
                    require(observed[0] == message_id, f"client {client_index + 1} observed a mismatched message id")
        submit_latencies.append((finished_at - started_at) * 1000.0)
        next_send += SEND_INTERVAL_SECONDS


async def _close(client: httpx.AsyncClient) -> None:
    try:
        await asyncio.wait_for(client.aclose(), timeout=CLOSE_TIMEOUT_SECONDS)
    except Exception:
        pass


def _p95(samples: list[float]) -> float:
    require(bool(samples), "no latency samples were recorded")
    ordered = sorted(samples)
    return ordered[max(0, math.ceil(len(ordered) * 0.95) - 1)]


async def measure(api_url: str, fixture: Fixture) -> dict[str, object]:
    expected = {
        fixture.baseline_seq + offset + 1: {
            "client_message_id": str(uuid.uuid4()),
            "content": f"chat-poll-load-{uuid.uuid4()}",
        }
        for offset in range(MESSAGE_COUNT)
    }
    seen_by_client = [set() for _ in range(CLIENT_COUNT)]
    observations: dict[tuple[int, int], tuple[int, float]] = {}
    submit_latencies: list[float] = []
    state_lock = asyncio.Lock()
    clients: list[httpx.AsyncClient] = []
    tasks: list[asyncio.Task[None]] = []
    try:
        writer_client = _client(fixture.writer_token)
        clients.append(writer_client)
        poll_clients = []
        for _ in range(CLIENT_COUNT):
            poll_client_instance = _client(fixture.recipient_token)
            clients.append(poll_client_instance)
            poll_clients.append(poll_client_instance)

        warm_started = time.monotonic()
        await asyncio.gather(
            request_json(writer_client, "GET", f"{api_url}/chat/groups/{fixture.group_id}/membership",
                         "writer warm-up", expected_status=200),
            *(warm_reader(client, api_url, fixture, warm_started + index * POLL_INTERVAL_SECONDS / CLIENT_COUNT)
              for index, client in enumerate(poll_clients)),
        )
        start_at = time.monotonic() + 0.1
        deadline = start_at + OVERALL_TIMEOUT_SECONDS
        tasks.append(
            asyncio.create_task(
                writer(
                    writer_client,
                    fixture,
                    expected,
                    submit_latencies,
                    observations,
                    state_lock,
                    start_at,
                    deadline,
                    api_url,
                )
            )
        )
        tasks.extend(
            asyncio.create_task(
                poll_client(
                    index,
                    poll_clients[index],
                    fixture,
                    expected,
                    seen_by_client,
                    observations,
                    state_lock,
                    start_at,
                    deadline,
                    api_url,
                )
            )
            for index in range(CLIENT_COUNT)
        )
        try:
            await asyncio.wait_for(asyncio.gather(*tasks), timeout=OVERALL_TIMEOUT_SECONDS)
        except BaseException:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            raise

        require(len(submit_latencies) == MESSAGE_COUNT, "submit latency samples were dropped")
        expected_observations = CLIENT_COUNT * MESSAGE_COUNT
        require(len(observations) == expected_observations, "visibility latency samples were dropped")
        visibility_latencies: list[float] = []
        for client_index, seen in enumerate(seen_by_client):
            require(len(seen) == MESSAGE_COUNT, f"client {client_index + 1} did not observe every message")
            for sequence, expected_message in expected.items():
                submitted_at = expected_message.get("submitted_at")
                observed = observations.get((client_index, sequence))
                require(isinstance(submitted_at, float) and observed is not None, "visibility sample was not recorded")
                require(observed[0] == expected_message.get("id"), "visibility sample had the wrong message id")
                visibility_latencies.append((observed[1] - submitted_at) * 1000.0)
        require(len(visibility_latencies) == expected_observations, "visibility latency samples were dropped")

        submit_p95 = _p95(submit_latencies)
        visibility_p95 = _p95(visibility_latencies)
        submit_gate_passed = submit_p95 <= 500.0
        visibility_gate_passed = visibility_p95 <= 5000.0
        gate_failures = []
        if not submit_gate_passed:
            gate_failures.append("submit P95 exceeded 500 ms")
        if not visibility_gate_passed:
            gate_failures.append("visibility P95 exceeded 5000 ms")
        return {
            "result": "passed" if not gate_failures else "failed",
            "scenario": "50 foreground HTTP polling clients of the same recipient + one writer",
            "transport": "HTTP history polling",
            "websocket_load_assertion": False,
            "clients": CLIENT_COUNT,
            "messages": MESSAGE_COUNT,
            "poll_interval_ms": int(POLL_INTERVAL_SECONDS * 1000),
            "send_interval_ms": int(SEND_INTERVAL_SECONDS * 1000),
            "submit_samples": len(submit_latencies),
            "visibility_samples": len(visibility_latencies),
            "authenticated_warmup_clients": len(clients),
            "submit_p95_ms": round(submit_p95, 3),
            "submit_max_ms": round(max(submit_latencies), 3),
            "visibility_p95_ms": round(visibility_p95, 3),
            "gates_ms": {"submit_p95": 500, "visibility_p95": 5000},
            "gates_passed": not gate_failures,
            **({"error": "; ".join(gate_failures)} if gate_failures else {}),
        }
    finally:
        await asyncio.gather(*(_close(client) for client in clients), return_exceptions=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api-url", required=True)
    parser.add_argument("--state", required=True, type=Path)
    args = parser.parse_args()
    try:
        api_url = validate_api_url(args.api_url)
        fixture = load_fixture(args.state)
        result = asyncio.run(measure(api_url, fixture))
    except LoadError as error:
        result = {"result": "failed", "error": str(error)}
        print(json.dumps(result, separators=(",", ":")))
        return 1
    except Exception as error:
        result = {"result": "failed", "error": f"unexpected {type(error).__name__}"}
        print(json.dumps(result, separators=(",", ":")))
        return 1
    print(json.dumps(result, separators=(",", ":")))
    return 0 if result.get("result") == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
