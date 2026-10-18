"""Deterministic API seed shared by the mandatory and focused API suites."""

from __future__ import annotations

import secrets
import string
from dataclasses import dataclass
from os import getenv
from typing import Any, Iterable
from uuid import uuid4

import httpx
import pytest

from tests.api.api_client import ApiClient


API_BASE_URL = getenv("TEST_API_BASE_URL", "http://localhost:9000/api/v1").rstrip("/")


@dataclass(frozen=True)
class Identity:
    username: str
    email: str
    phone: str
    password: str


@dataclass
class Seed:
    owner: ApiClient
    reader: ApiClient
    owner_id: int
    reader_id: int
    section_id: int
    thread_id: int
    post_id: int
    thread_title: str
    thread_content: str
    comment_content: str


def api_base_url(config: pytest.Config | None = None) -> str:
    """Resolve the API endpoint from pytest's CLI option or the environment."""

    if config is not None:
        try:
            configured = config.getoption("api_url")
        except (AttributeError, ValueError):
            configured = None
        if configured:
            return str(configured).rstrip("/")
    return API_BASE_URL


def new_identity(role: str) -> Identity:
    suffix = uuid4().hex[:10]
    username = f"c{role}{suffix}"
    # The fixed prefix satisfies the password validator's upper/lower/digit
    # requirements even when the random suffix contains no digit by chance.
    password = "Ci1!" + "".join(secrets.choice(string.ascii_letters + string.digits) for _ in range(28))
    return Identity(
        username=username,
        email=f"{username}@example.com",
        phone=f"13{secrets.randbelow(10**9):09d}",
        password=password,
    )


def json_body(response: httpx.Response, label: str) -> dict[str, Any]:
    try:
        body = response.json()
    except (ValueError, TypeError) as exc:
        pytest.fail(f"{label} returned a non-JSON response ({type(exc).__name__})")
    if not isinstance(body, dict):
        pytest.fail(f"{label} returned an unexpected response shape")
    return body


def payload(body: dict[str, Any]) -> dict[str, Any]:
    nested = body.get("data")
    return nested if isinstance(nested, dict) else body


def items(body: dict[str, Any]) -> list[dict[str, Any]]:
    values = payload(body).get("items")
    return [item for item in values if isinstance(item, dict)] if isinstance(values, list) else []


def require_status(response: httpx.Response, expected: Iterable[int], label: str) -> None:
    allowed = tuple(expected)
    if response.status_code not in allowed:
        # Do not include response bodies: an error response can echo submitted
        # email, phone, or other generated test credentials.
        pytest.fail(f"{label} returned HTTP {response.status_code}; expected {allowed}")


def require_id(value: Any, label: str) -> int:
    candidates: list[Any] = []
    if isinstance(value, dict):
        candidates.extend((value.get("id"), value.get("userId"), value.get("user_id")))
        nested = value.get("user")
        if isinstance(nested, dict):
            candidates.extend((nested.get("id"), nested.get("userId"), nested.get("user_id")))
    for candidate in candidates:
        try:
            if candidate is not None:
                return int(candidate)
        except (TypeError, ValueError):
            continue
    pytest.fail(f"{label} did not return a numeric id")


def register(client: ApiClient, identity: Identity, label: str) -> int:
    response = client.register(
        username=identity.username,
        email=identity.email,
        phone=identity.phone,
        password=identity.password,
    )
    require_status(response, (201,), label)
    return require_id(payload(json_body(response, label)), label)


def login(client: ApiClient, identity: Identity, label: str) -> int:
    response = client.login_email_password(identity.email, identity.password)
    require_status(response, (200,), label)
    body = json_body(response, label)
    data = payload(body)
    token = data.get("accessToken") or data.get("access_token") or data.get("token")
    if not isinstance(token, str) or not token:
        pytest.fail(f"{label} did not return an access token")
    client.set_token(token)
    return require_id(data.get("user", data), label)


@pytest.fixture(scope="session")
def ci_seed(request: pytest.FixtureRequest) -> Seed:
    """Create an isolated owner/reader/thread/comment graph for API tests."""

    base_url = api_base_url(request.config)
    owner = ApiClient(base_url)
    reader = ApiClient(base_url)
    owner_identity = new_identity("owner")
    reader_identity = new_identity("reader")
    thread_id: int | None = None
    post_id: int | None = None

    try:
        owner_id = register(owner, owner_identity, "owner registration")
        reader_id = register(reader, reader_identity, "reader registration")
        if login(owner, owner_identity, "owner login") != owner_id:
            pytest.fail("owner login returned a different user id")
        if login(reader, reader_identity, "reader login") != reader_id:
            pytest.fail("reader login returned a different user id")

        sections_response = owner.list_sections(page=1, size=1)
        require_status(sections_response, (200,), "section lookup")
        sections = items(json_body(sections_response, "section lookup"))
        if not sections:
            pytest.fail("isolated Compose database did not expose a writable section")
        section_id = require_id(sections[0], "section lookup")

        thread_title = f"CI API contract {uuid4().hex[:12]}"
        thread_content = "CI API contract thread content with a unique marker."
        thread_response = owner.create_thread(section_id, thread_title, thread_content)
        require_status(thread_response, (201,), "thread seed")
        thread_id = require_id(payload(json_body(thread_response, "thread seed")), "thread seed")

        comment_content = f"CI API contract comment {uuid4().hex[:12]}"
        post_response = reader.create_post(thread_id, comment_content)
        require_status(post_response, (201,), "comment seed")
        post_id = require_id(payload(json_body(post_response, "comment seed")), "comment seed")
        seed = Seed(
            owner=owner,
            reader=reader,
            owner_id=owner_id,
            reader_id=reader_id,
            section_id=section_id,
            thread_id=thread_id,
            post_id=post_id,
            thread_title=thread_title,
            thread_content=thread_content,
            comment_content=comment_content,
        )
    except BaseException:
        if post_id is not None:
            try:
                reader.delete_post(post_id)
            except Exception:
                pass
        if thread_id is not None:
            try:
                owner.delete_thread(thread_id)
            except Exception:
                pass
        owner.close()
        reader.close()
        raise

    try:
        yield seed
    finally:
        cleanup_errors: list[str] = []

        def cleanup(response: httpx.Response, expected: tuple[int, ...], label: str) -> None:
            if response.status_code not in expected:
                cleanup_errors.append(f"{label} returned HTTP {response.status_code}")

        try:
            cleanup(owner.unbookmark_thread(thread_id), (200,), "bookmark cleanup")
        except Exception as exc:
            cleanup_errors.append(f"bookmark cleanup raised {type(exc).__name__}")
        try:
            cleanup(reader.unlike_thread(thread_id), (200,), "thread-like cleanup")
        except Exception as exc:
            cleanup_errors.append(f"thread-like cleanup raised {type(exc).__name__}")
        try:
            cleanup(reader.delete_post(post_id), (204,), "comment cleanup")
        except Exception as exc:
            cleanup_errors.append(f"comment cleanup raised {type(exc).__name__}")
        try:
            cleanup(owner.delete_thread(thread_id), (204,), "thread cleanup")
        except Exception as exc:
            cleanup_errors.append(f"thread cleanup raised {type(exc).__name__}")
        owner.close()
        reader.close()
        if cleanup_errors:
            pytest.fail("CI seed cleanup failed: " + "; ".join(cleanup_errors))
