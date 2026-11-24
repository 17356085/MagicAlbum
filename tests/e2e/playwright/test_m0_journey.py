"""M0/R01/R04 browser journey against an isolated Compose stack.

The journey owns all state it creates: two UUID based users, one thread, one
comment, one report, notifications, and a bookmark transition.  It keeps the
browser assertions focused on the user-visible flow and re-reads every durable
result through the API before considering the step complete.

The shared Playwright fixtures authenticate an account supplied through
``tests/.env``.  This scenario deliberately uses its own Playwright contexts
because it must register two fresh users and exercise the UI registration path.
"""

from __future__ import annotations

import json
import os
import re
import secrets
import string
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable
from urllib.parse import parse_qs, urlparse
from uuid import uuid4

import pytest
from playwright.sync_api import Browser, BrowserContext, Page, Playwright, expect

from tests.api.api_client import ApiClient


BASE_URL = os.getenv("TEST_BASE_URL", "http://localhost:5173").rstrip("/")
API_BASE_URL = os.getenv("TEST_API_BASE_URL", "http://localhost:9000/api/v1").rstrip("/")
ARTIFACT_DIR = Path(os.getenv("TEST_ARTIFACT_DIR", "outputs/m0-test"))
POLL_TIMEOUT_SECONDS = float(os.getenv("TEST_E2E_POLL_TIMEOUT", "20"))


@dataclass(frozen=True)
class AccountIdentity:
    username: str
    email: str
    phone: str
    password: str


def _new_identity(role: str) -> AccountIdentity:
    suffix = uuid4().hex[:12]
    # Keep the username inside the browser validator's 3-20 character range.
    username = f"m0{role}{suffix}"
    password = "M0" + "".join(secrets.choice(string.ascii_letters + string.digits) for _ in range(24)) + "aA1"
    phone = f"13{secrets.randbelow(10**9):09d}"
    return AccountIdentity(
        username=username,
        email=f"{username}@example.com",
        phone=phone,
        password=password,
    )


def _json_body(response: Any, label: str) -> dict[str, Any]:
    try:
        body = response.json()
    except Exception as exc:  # pragma: no cover - only reached on a broken service response
        pytest.fail(f"{label} returned a non-JSON response ({type(exc).__name__})")
    if not isinstance(body, dict):
        pytest.fail(f"{label} returned an unexpected response shape")
    return body


def _unwrap(body: dict[str, Any]) -> dict[str, Any]:
    nested = body.get("data")
    return nested if isinstance(nested, dict) else body


def _extract_id(value: Any) -> int | None:
    if not isinstance(value, dict):
        return None
    for key in ("id", "userId", "user_id"):
        candidate = value.get(key)
        if candidate is not None:
            try:
                return int(candidate)
            except (TypeError, ValueError):
                return None
    return None


def _extract_user(body: dict[str, Any]) -> dict[str, Any] | None:
    payload = _unwrap(body)
    for key in ("user", "profile", "userInfo", "account"):
        value = payload.get(key)
        if isinstance(value, dict):
            return value
    # /users/me returns the user object directly.
    return payload if _extract_id(payload) is not None else None


def _extract_token(body: dict[str, Any]) -> str:
    payload = _unwrap(body)
    for key in ("accessToken", "access_token", "token", "jwt"):
        value = payload.get(key)
        if isinstance(value, str) and value.strip():
            return value
    return ""


def _extract_items(body: dict[str, Any]) -> list[dict[str, Any]]:
    payload: Any = body
    if isinstance(body.get("data"), dict):
        payload = body["data"]
    items = payload.get("items") if isinstance(payload, dict) else None
    return [item for item in items if isinstance(item, dict)] if isinstance(items, list) else []


def _assert_status(response: Any, allowed: tuple[int, ...], label: str) -> None:
    if response.status_code not in allowed:
        # Deliberately omit response bodies: API error bodies can echo input in
        # some deployments, and this test must never expose generated secrets.
        pytest.fail(f"{label} returned HTTP {response.status_code}")


def _request(
    client: ApiClient,
    method: str,
    path: str,
    *,
    json: dict[str, Any] | None = None,
    params: dict[str, Any] | None = None,
) -> Any:
    """Use the already-authenticated ApiClient without changing shared helpers."""

    return client._client.request(  # noqa: SLF001 - test-only endpoint not in the common helper yet
        method,
        f"{client.base_url}{path}",
        json=json,
        params=params,
        headers=client.headers,
    )


def _eventually(label: str, predicate: Callable[[], Any]) -> Any:
    deadline = time.monotonic() + POLL_TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        try:
            result = predicate()
            if result:
                return result
        except Exception:
            # Compose services may be briefly unavailable while the outbox or
            # cache catches up.  The final failure remains a generic diagnostic.
            pass
        time.sleep(0.25)
    pytest.fail(f"{label} was not observable through the API within {POLL_TIMEOUT_SECONDS:g}s")


def _verify_owned_section_filter(
    page: Page,
    *,
    route: str,
    endpoint: str,
    section_id: int,
    expected_item_id: int,
    expected_text: str,
) -> None:
    """Verify a real owned-list section filter through its browser response."""

    page.goto(f"{BASE_URL}{route}", wait_until="domcontentloaded")
    section_filter = page.get_by_label("按分区筛选", exact=True)
    expect(section_filter).to_be_visible(timeout=10000)
    expect(section_filter.locator(f'option[value="{section_id}"]')).to_have_count(1, timeout=10000)

    with page.expect_response(
        lambda response: (
            response.request.method == "GET"
            and urlparse(response.url).path == endpoint
            and parse_qs(urlparse(response.url).query).get("sectionId") == [str(section_id)]
        ),
        timeout=10000,
    ) as filtered_response_info:
        section_filter.select_option(str(section_id))

    filtered_response = filtered_response_info.value
    if filtered_response.status != 200:
        pytest.fail(f"{endpoint} section filter returned HTTP {filtered_response.status}")
    filtered_items = _extract_items(_json_body(filtered_response, f"{endpoint} section filter"))
    if not any(_extract_id(item) == expected_item_id for item in filtered_items):
        pytest.fail(f"{endpoint} section filter did not include entity {expected_item_id}")
    item_id = page.get_by_text(f"ID: {expected_item_id}", exact=True)
    result_row = page.get_by_role("listitem").filter(has=item_id)
    expect(result_row).to_have_count(1, timeout=10000)
    expect(result_row.get_by_text(expected_text, exact=True)).to_be_visible(timeout=10000)


def _register_via_api(client: ApiClient, identity: AccountIdentity) -> int:
    response = client.register(
        username=identity.username,
        email=identity.email,
        phone=identity.phone,
        password=identity.password,
    )
    _assert_status(response, (200, 201), "test-account registration")
    user = _extract_user(_json_body(response, "test-account registration"))
    user_id = _extract_id(user)
    if user_id is None:
        pytest.fail("test-account registration did not return a user id")
    return user_id


def _login_via_api(client: ApiClient, identity: AccountIdentity) -> tuple[str, int, dict[str, Any]]:
    response = client.login_email_password(identity.email, identity.password)
    _assert_status(response, (200,), "test-account login")
    body = _json_body(response, "test-account login")
    token = _extract_token(body)
    if not token:
        pytest.fail("test-account login did not return an access token")
    client.set_token(token)

    user = _extract_user(body)
    if user is None or _extract_id(user) is None:
        current = client.get_current_user()
        _assert_status(current, (200,), "current-user readback")
        user = _extract_user(_json_body(current, "current-user readback"))
    user_id = _extract_id(user)
    if user is None or user_id is None:
        pytest.fail("authenticated test account did not expose a user id")
    return token, user_id, user


def _register_and_login_ui(page: Page, identity: AccountIdentity) -> str:
    """Register and log in account A through the real auth modal."""

    page.goto(f"{BASE_URL}/discover", wait_until="domcontentloaded")
    login_entry = page.get_by_role("button", name="登录", exact=True)
    expect(login_entry).to_be_visible(timeout=10000)
    login_entry.click()

    dialog = page.get_by_role("dialog").first
    expect(dialog).to_be_visible(timeout=10000)
    dialog.get_by_role("button", name="注册", exact=True).first.click()
    expect(dialog.locator("#auth-register-username")).to_be_visible(timeout=5000)

    dialog.locator("#auth-register-username").fill(identity.username)
    dialog.locator("#auth-register-phone").fill(identity.phone)
    dialog.locator("#auth-register-email").fill(identity.email)
    dialog.locator("#auth-register-password").fill(identity.password)
    dialog.locator("#auth-register-confirm-password").fill(identity.password)
    dialog.locator("form").get_by_role("button", name="注册", exact=True).click()

    # The isolated Compose runner configures verification mode as off.  A
    # mandatory browser journey must fail if that contract is changed; skipping
    # would turn a missing registration capability into a false green run.
    if dialog.get_by_text("人工验证", exact=False).is_visible():
        pytest.fail("浏览器注册需要人工验证；隔离 Compose 栈必须关闭外部验证码")
    expect(page.get_by_role("status").filter(has_text="注册成功，请登录")).to_be_visible(timeout=10000)

    # Registration returns the modal to its default phone-password mode while
    # preserving the submitted phone/email as seeds.  Switch explicitly before
    # filling the email field; the email input is not mounted in phone mode.
    dialog.get_by_role("button", name="切换到邮箱登录", exact=True).click()
    expect(dialog.locator("#auth-login-email")).to_be_visible(timeout=5000)
    dialog.locator("#auth-login-email").fill(identity.email)
    dialog.locator("#auth-login-password").fill(identity.password)
    dialog.locator("form").get_by_role("button", name="登录", exact=True).click()
    expect(page.get_by_role("dialog")).to_have_count(0, timeout=10000)

    # The UI must have stored a real token before the API readbacks begin.
    token = page.evaluate("localStorage.getItem('accessToken')")
    if not isinstance(token, str) or not token or token.startswith("mock-token-"):
        pytest.fail("browser login did not persist a real access token")
    return token


def _seed_authenticated_page(page: Page, token: str, user: dict[str, Any]) -> None:
    page.goto(BASE_URL, wait_until="domcontentloaded")
    page.evaluate("localStorage.clear(); sessionStorage.clear()")
    page.evaluate(
        """({ token, user }) => {
          localStorage.setItem('accessToken', token)
          localStorage.setItem('currentUser', JSON.stringify(user))
        }""",
        {"token": token, "user": user},
    )
    page.goto(f"{BASE_URL}/discover", wait_until="domcontentloaded")
    expect(page.get_by_role("button", name="通知", exact=True)).to_be_visible(timeout=10000)


def _verify_home_overview(page: Page, user_id: int) -> None:
    endpoint = "/api/v1/home/overview"
    with page.expect_response(lambda response: urlparse(response.url).path == endpoint) as overview:
        page.goto(f"{BASE_URL}/discover", wait_until="domcontentloaded")
    response = overview.value
    assert response.status == 200
    assert "no-store" in response.headers.get("cache-control", "")
    payload = response.json()
    assert payload["unavailable"] == []
    assert _extract_id(payload["profile"]) == user_id
    assert isinstance(payload["unreadNotifications"], int)
    assert _extract_items(payload["sections"])
    assert isinstance(payload["threads"].get("items"), list)
    assert payload["following"] is not None


def _verify_search_history(page: Page, query: str) -> None:
    page.goto(f"{BASE_URL}/discover", wait_until="domcontentloaded")
    search = page.get_by_role("textbox", name="搜索关键词")
    search.fill(query)
    search.press("Enter")
    page.wait_for_url(lambda url: parse_qs(urlparse(url).query).get("q") == [query])
    expect(page.get_by_text(query, exact=True).first).to_be_visible()

    page.goto(f"{BASE_URL}/discover", wait_until="domcontentloaded")
    search.click()
    expect(page.get_by_role("button", name=query, exact=True)).to_be_visible()
    page.get_by_role("button", name="清除搜索历史").click()
    expect(page.get_by_role("button", name=query, exact=True)).to_have_count(0)


def _verify_popular_search(page: Page, client_a: ApiClient, client_b: ApiClient) -> None:
    endpoint = "/search/popular"
    query = "社区好帖联合验收"
    payload = {"type": "threads", "query": query}
    visitor = ApiClient(API_BASE_URL)
    temporary_accounts = []
    try:
        _assert_status(_request(visitor, "POST", endpoint, json=payload), (401,), "anonymous search recording")
        for _ in range(3):
            _assert_status(_request(client_a, "POST", endpoint, json=payload), (204,), "duplicate search recording")
        _assert_status(_request(client_b, "POST", endpoint, json=payload), (204,), "second searcher")

        def ranking():
            response = _request(visitor, "GET", endpoint, params={"type": "threads"})
            _assert_status(response, (200,), "public popular searches")
            return response.json()

        assert query not in ranking()
        for _ in range(3):
            account = ApiClient(API_BASE_URL)
            temporary_accounts.append(account)
            identity = _new_identity("s")
            _register_via_api(account, identity)
            _login_via_api(account, identity)
            _assert_status(_request(account, "POST", endpoint, json=payload), (204,), "distinct searcher")
        assert query in ranking()
        page.goto(f"{BASE_URL}/discover", wait_until="domcontentloaded")
        page.get_by_role("textbox", name="搜索关键词").click()
        expect(page.get_by_role("button", name=query, exact=True)).to_be_visible()
    finally:
        visitor.close()
        for account in temporary_accounts:
            account.close()


def _chat_frame_count(page: Page, signal: str) -> int:
    return page.evaluate("signal => window.chatRealtimeProbe.frames.filter(frame => frame === signal).length", signal)


def _wait_for_chat_frame(page: Page, signal: str, after: int = 0) -> None:
    page.wait_for_function(
        """({ signal, after }) =>
            window.chatRealtimeProbe.frames.filter(frame => frame === signal).length > after""",
        arg={"signal": signal, "after": after},
        timeout=30_000,
    )


def _send_chat_from_browser(page: Page, endpoint: str, content: str, status: int) -> None:
    page.get_by_role("textbox", name="消息内容").fill(content)
    with page.expect_response(
        lambda response: response.request.method == "POST" and urlparse(response.url).path == endpoint
    ) as sent:
        page.get_by_role("button", name="发送", exact=True).click()
    assert sent.value.status == status


def _verify_realtime_chat(
    page_a: Page, page_b: Page, client_a: ApiClient, client_b: ApiClient,
    user_a_id: int, user_b_id: int, username_b: str,
) -> None:
    page_a.goto(f"{BASE_URL}/users/{user_b_id}", wait_until="domcontentloaded")
    with page_a.expect_response(
        lambda response: response.request.method == "PUT"
        and urlparse(response.url).path == f"/api/v1/chat/friend-requests/{user_b_id}"
    ) as friend_request:
        page_a.get_by_role("button", name="添加好友", exact=True).click()
    assert friend_request.value.json()["status"] == "SENT"
    page_b.goto(f"{BASE_URL}/messages", wait_until="domcontentloaded")
    page_b.get_by_role("button", name="好友申请", exact=True).click()
    with page_b.expect_response(
        lambda response: response.request.method == "PUT"
        and urlparse(response.url).path == f"/api/v1/chat/friend-requests/{user_a_id}/decision"
    ) as accepted:
        page_b.get_by_role("button", name="接受好友申请", exact=True).click()
    assert accepted.value.json()["status"] == "FRIEND"
    detail = _json_body(_request(client_a, "GET", f"/chat/direct/{user_b_id}"), "friend contact decision")
    assert detail["mutual"] is False and detail["friend"] is True
    page_b.add_init_script("""(() => {
        const probe = window.chatRealtimeProbe = { frames: [], socket: null, paused: false, blockedAttempts: 0 };
        window.WebSocket = class extends window.WebSocket {
            constructor(...args) {
                const isChat = new URL(args[0], location.href).pathname === '/api/v1/chat/realtime';
                if (isChat && probe.paused) {
                    probe.blockedAttempts++;
                    throw new DOMException('Test WebSocket transport unavailable', 'NetworkError');
                }
                super(...args);
                if (!isChat) return;
                probe.socket = this;
                this.addEventListener('message', event => probe.frames.push(event.data));
            }
        };
    })();""")
    page_a.goto(f"{BASE_URL}/messages?peer={user_b_id}", wait_until="domcontentloaded")
    page_b.goto(f"{BASE_URL}/messages?peer={user_a_id}", wait_until="domcontentloaded")
    page_b.bring_to_front()
    _wait_for_chat_frame(page_b, "ready")

    marker = uuid4().hex[:8]
    direct_message = f"Realtime direct {marker}"
    changes = _chat_frame_count(page_b, "changed")
    _send_chat_from_browser(page_a, f"/api/v1/chat/direct/{user_b_id}/messages", direct_message, 201)
    _wait_for_chat_frame(page_b, "changed", changes)
    expect(page_b.locator(".messages__text")).to_have_text([direct_message], timeout=10_000)

    group_title = f"Realtime group {marker}"
    page_a.get_by_role("button", name="发起群聊", exact=True).click()
    page_a.get_by_label("群名称").fill(group_title)
    page_a.get_by_role("button", name=username_b, exact=True).click()
    changes = _chat_frame_count(page_b, "changed")
    with page_a.expect_response(
        lambda response: response.request.method == "POST"
        and urlparse(response.url).path == "/api/v1/chat/groups"
    ) as created:
        page_a.get_by_role("button", name="创建群聊", exact=True).click()
    assert created.value.status == 201
    group_id = created.value.json()["id"]
    group_messages = f"/chat/groups/{group_id}/messages"
    _wait_for_chat_frame(page_b, "changed", changes)
    _assert_status(
        _request(client_b, "GET", f"/chat/conversations/{group_id}/messages"),
        (404,),
        "invited account cannot read group history",
    )

    page_b.get_by_role("button", name=re.compile(r"^请求\s*1$")).click()
    page_b.get_by_role("button", name=re.compile(re.escape(group_title))).click()
    changes = _chat_frame_count(page_b, "changed")
    page_b.get_by_role("button", name="加入群聊", exact=True).click()
    _wait_for_chat_frame(page_b, "changed", changes)
    expect(page_b.get_by_role("textbox", name="消息内容")).to_be_visible()

    group_message = f"Realtime group start {marker}"
    changes = _chat_frame_count(page_b, "changed")
    _send_chat_from_browser(page_a, f"/api/v1{group_messages}", group_message, 200)
    _wait_for_chat_frame(page_b, "changed", changes)
    history = page_b.locator(".messages__text")
    expect(history).to_have_text([group_message], timeout=10_000)

    ready_count = _chat_frame_count(page_b, "ready")
    page_b.evaluate("""() => {
        const probe = window.chatRealtimeProbe;
        probe.paused = true;
        probe.socket.close(4000, 'test network interruption');
    }""")
    page_b.bring_to_front()

    def store_group_message(content: str) -> dict[str, Any]:
        sent = _request(
            client_a, "POST", group_messages,
            json={"content": content, "clientMessageId": str(uuid4())},
        )
        _assert_status(sent, (200,), "store group message while the recipient has no realtime connection")
        return _json_body(sent, "stored group message")

    fallback_message = f"Realtime polling fallback {marker}"
    store_group_message(fallback_message)
    expect(history).to_have_text([group_message, fallback_message], timeout=10_000)
    page_b.wait_for_function("window.chatRealtimeProbe.blockedAttempts > 0")
    assert _chat_frame_count(page_b, "ready") == ready_count

    page_b.context.set_offline(True)
    backlog = [f"Realtime offline {marker} {sequence:02d}" for sequence in range(1, 32)]
    try:
        for content in backlog:
            store_group_message(content)
        expect(history).to_have_text([group_message, fallback_message])
    finally:
        page_b.evaluate("window.chatRealtimeProbe.paused = false")
        page_b.context.set_offline(False)

    _wait_for_chat_frame(page_b, "ready", ready_count)
    expect(history).to_have_text([group_message, fallback_message, *backlog], timeout=15_000)

    ready_count = _chat_frame_count(page_b, "ready")
    page_b.evaluate("""() => {
        const probe = window.chatRealtimeProbe;
        probe.paused = true;
        probe.socket.close(4000, 'test offline membership changes');
    }""")
    page_b.context.set_offline(True)
    history_path = f"/chat/conversations/{group_id}/messages"
    after_rejoin = f"Realtime after rejoin {marker}"
    try:
        _assert_status(
            _request(client_b, "DELETE", f"/chat/groups/{group_id}/members/{user_b_id}"),
            (204,), "offline browser account leaves the group from another client",
        )
        _assert_status(_request(client_b, "GET", history_path), (404,), "former member cannot read group history")
        missed_while_absent = store_group_message(f"Realtime while absent {marker}")
        _assert_status(
            _request(client_a, "POST", f"/chat/groups/{group_id}/invitations", json={"userId": user_b_id}),
            (204,), "group owner reinvites the former member",
        )
        _assert_status(
            _request(client_b, "PUT", f"/chat/groups/{group_id}/invitations/me", json={"accept": True}),
            (204,), "former member rejoins while the browser remains offline",
        )
        fresh = store_group_message(after_rejoin)
        result = _json_body(_request(client_b, "GET", history_path), "history after rejoining")
        assert result["visibleFromSeq"] == fresh["seq"] == missed_while_absent["seq"] + 1
        assert [item["id"] for item in result["items"]] == [fresh["id"]]
        expect(history).to_have_text([group_message, fallback_message, *backlog])
    finally:
        page_b.evaluate("window.chatRealtimeProbe.paused = false")
        page_b.context.set_offline(False)

    _wait_for_chat_frame(page_b, "ready", ready_count)
    expect(history).to_have_text([after_rejoin], timeout=15_000)

    changes = _chat_frame_count(page_b, "changed")
    recalled = page_a.locator(".messages__bubble").filter(has_text=after_rejoin)
    with page_a.expect_response(
        lambda response: response.request.method == "DELETE"
        and urlparse(response.url).path == f"/api/v1{history_path}/{fresh['id']}"
    ) as recalled_response:
        recalled.get_by_role("button", name="撤回", exact=True).click()
    assert recalled_response.value.status == 204
    _wait_for_chat_frame(page_b, "changed", changes)
    expect(history).to_have_text(["消息已撤回"], timeout=10_000)

    block_path = f"/chat/blocks/{user_a_id}"
    direct_path = f"/chat/direct/{user_b_id}"
    _assert_status(_request(client_b, "PUT", block_path), (204,), "recipient blocks the direct message sender")
    try:
        _assert_status(
            _request(client_a, "POST", f"{direct_path}/messages",
                     json={"content": "must not be stored", "clientMessageId": str(uuid4())}),
            (403,), "a new message is denied after the recipient block commits",
        )
        detail = _json_body(_request(client_a, "GET", direct_path), "blocked direct conversation")
        assert detail["canSend"] is False
        conversation_id = detail["id"]
        messages = _json_body(
            _request(client_a, "GET", f"/chat/conversations/{conversation_id}/messages"),
            "blocked direct conversation history",
        )
        assert [item["content"] for item in messages["items"]] == [direct_message]
    finally:
        _assert_status(_request(client_b, "DELETE", block_path), (204,), "recipient removes the test block")


def _read_thread_if_ready(client: ApiClient, thread_id: int, expected_title: str, expected_author: int) -> dict[str, Any] | None:
    response = client.get_thread(thread_id)
    if response.status_code != 200:
        return None
    body = _json_body(response, "thread readback")
    if body.get("title") != expected_title or int(body.get("authorId") or 0) != expected_author:
        return None
    return body


def _read_post_if_ready(client: ApiClient, thread_id: int, content: str, author_id: int) -> dict[str, Any] | None:
    response = client.list_posts(thread_id, page=1, size=50)
    if response.status_code != 200:
        return None
    for item in _extract_items(_json_body(response, "post list readback")):
        if item.get("content") == content and int(item.get("authorId") or 0) == author_id:
            return item
    return None


def _read_notifications(client: ApiClient) -> list[dict[str, Any]] | None:
    response = client.list_notifications(page=1, size=50)
    if response.status_code != 200:
        return None
    return _extract_items(_json_body(response, "notification readback"))


def _read_notification_state(client: ApiClient, notification_id: int) -> dict[str, Any] | None:
    notifications = _read_notifications(client)
    if notifications is None:
        return None
    for item in notifications:
        if int(item.get("id") or 0) == notification_id:
            return item
    return None


def _read_report_by_replay(
    client: ApiClient,
    thread_id: int,
    detail: str,
    reporter_id: int,
    expected_id: int,
) -> dict[str, Any]:
    """Read back the persisted report through its documented idempotent replay.

    The public API intentionally exposes report creation but no reporter-owned
    GET endpoint.  Replaying the same reporter/target pair returns the original
    persisted metadata, which gives this test a server readback without using a
    privileged account.
    """

    replay = client.create_report("THREAD", thread_id, "OTHER", detail)
    _assert_status(replay, (201,), "report persistence readback")
    body = _json_body(replay, "report persistence readback")
    if int(body.get("id") or 0) != expected_id:
        pytest.fail("report persistence readback returned a different report")
    if int(body.get("reporterId") or 0) != reporter_id:
        pytest.fail("report persistence readback returned a different reporter")
    if str(body.get("targetType") or "").upper() != "THREAD" or int(body.get("targetId") or 0) != thread_id:
        pytest.fail("report persistence readback returned a different target")
    if (
        str(body.get("status") or "").upper() != "PENDING"
        or str(body.get("reason") or "").upper() != "OTHER"
        or body.get("detail") != detail
    ):
        pytest.fail("report persistence readback did not preserve the submitted report")
    return body


def _capture_failure(pages: list[Page]) -> None:
    """Capture safe diagnostics without retaining passwords or auth tokens."""

    try:
        ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)
    except Exception:
        return
    for index, page in enumerate(pages, start=1):
        try:
            page.locator("input[type='password']").evaluate_all(
                "elements => elements.forEach(element => { element.value = '' })"
            )
            page.evaluate(
                "localStorage.removeItem('accessToken'); localStorage.removeItem('currentUser'); sessionStorage.clear()"
            )
            page.screenshot(path=str(ARTIFACT_DIR / f"m0-journey-failure-{index}.png"), full_page=True)
        except Exception:
            # A failure during navigation must not hide the original assertion.
            pass


class _CaptureOnFailure:
    """Capture browser pages before the outer Playwright context closes."""

    def __init__(self, pages: Callable[[], list[Page]]) -> None:
        self._pages = pages

    def __enter__(self) -> "_CaptureOnFailure":
        return self

    def __exit__(self, exc_type: type[BaseException] | None, _value: BaseException | None, _traceback: Any) -> bool:
        if exc_type is not None:
            _capture_failure(self._pages())
        return False


def _write_fixture(
    *,
    owner_id: int,
    reader_id: int,
    thread_id: int,
    post_id: int,
    report_id: int,
    reply_notification_id: int,
    like_notification_id: int,
) -> None:
    """Persist non-sensitive IDs for the runner's post-run API/SQL readback."""

    ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)
    fixture = {
        "schemaVersion": 1,
        "ownerId": owner_id,
        "readerId": reader_id,
        "threadId": thread_id,
        "postId": post_id,
        "reportId": report_id,
        "replyNotificationId": reply_notification_id,
        "likeNotificationId": like_notification_id,
    }
    (ARTIFACT_DIR / "fixture.json").write_text(
        json.dumps(fixture, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


@pytest.mark.p0
@pytest.mark.ui
@pytest.mark.auth
@pytest.mark.slow
def test_m0_r01_r04_browser_journey(
    playwright_instance: Playwright, record_testsuite_property: Callable[[str, object], None]
) -> None:
    """Exercise registration, content interaction, notification, report, and isolation paths."""

    identity_a = _new_identity("a")
    identity_b = _new_identity("b")
    client_a = ApiClient(API_BASE_URL)
    client_b = ApiClient(API_BASE_URL)
    page_a: Page | None = None
    page_b: Page | None = None
    context_a: BrowserContext | None = None
    context_b: BrowserContext | None = None
    browser: Browser | None = None
    thread_id: int | None = None
    post_id: int | None = None

    try:
        with _CaptureOnFailure(
            lambda: [page for page in (page_a, page_b) if page is not None]
        ):
            channel = os.getenv("TEST_PLAYWRIGHT_CHANNEL", "chrome").strip() or None
            browser = playwright_instance.chromium.launch(
                channel=channel,
                headless=True,
                args=["--no-sandbox", "--disable-setuid-sandbox"],
            )
            context_a = browser.new_context(viewport={"width": 1280, "height": 900}, locale="zh-CN")
            page_a = context_a.new_page()

            # Account A is created and authenticated through the browser UI.
            _register_and_login_ui(page_a, identity_a)
            _, user_a_id, user_a = _login_via_api(client_a, identity_a)

            paged_sections = os.getenv("TEST_M0_PAGED_SECTIONS") == "1"
            sections = client_a.list_sections(page=2 if paged_sections else 1, size=100 if paged_sections else 1)
            _assert_status(sections, (200,), "section lookup")
            section_page = _json_body(sections, "section lookup")
            section_items = _extract_items(section_page)
            if not section_items or _extract_id(section_items[0]) is None:
                pytest.fail("isolated stack did not expose a writable section")
            section_id = int(section_items[0]["id"])
            if paged_sections:
                assert section_page["page"] == 2 and section_page["size"] == 100
                assert len(section_items) >= 2 and section_page["total"] > 101

            # Account B is created through the API only to keep the browser
            # registration assertion focused on the first account.
            _register_via_api(client_b, identity_b)
            token_b, user_b_id, user_b = _login_via_api(client_b, identity_b)

            thread_title = f"M0 browser journey {uuid4().hex[:12]}"
            thread_content = "M0 browser content with a unique marker."
            page_a.goto(f"{BASE_URL}/threads/new", wait_until="domcontentloaded")
            expect(page_a.get_by_role("heading", name="发帖", exact=True)).to_be_visible(timeout=10000)
            page_a.wait_for_function(
                """() => document.querySelectorAll('#thread-create-section option').length > 1""",
                timeout=10000,
            )
            page_a.locator("#thread-create-section").select_option(str(section_id))
            page_a.locator("#thread-create-title").fill(thread_title)
            page_a.get_by_text("标签（可选）", exact=True).click()
            expect(page_a.get_by_label("标签", exact=True)).to_be_visible()
            page_a.locator("#thread-create-tags").fill("m0journey")
            page_a.locator("textarea[placeholder='请输入帖子正文，支持 Markdown']").fill(thread_content)
            page_a.get_by_role("button", name="发布", exact=True).click()
            expect(page_a.get_by_role("status").filter(has_text="发布成功")).to_be_visible(timeout=10000)
            view_link = page_a.get_by_role("link", name="查看帖子", exact=True)
            expect(view_link).to_be_visible(timeout=10000)
            href = view_link.get_attribute("href") or ""
            try:
                thread_id = int(href.rstrip("/").rsplit("/", 1)[1])
            except (ValueError, IndexError):
                pytest.fail("browser post flow did not expose a valid thread link")

            thread = _eventually(
                "browser-created thread",
                lambda: _read_thread_if_ready(client_a, thread_id, thread_title, user_a_id),
            )
            assert thread["content"] == thread_content
            assert int(thread["sectionId"]) == section_id

            if paged_sections:
                edit_section_id = int(section_items[1]["id"])
                page_a.goto(f"{BASE_URL}/threads/{thread_id}/edit", wait_until="domcontentloaded")
                edit_section = page_a.get_by_label("分区", exact=True)
                expect(edit_section).to_have_value(str(section_id), timeout=10000)
                edit_section.select_option(str(edit_section_id))
                with page_a.expect_response(
                    lambda response: response.request.method == "PATCH" and response.url.endswith(f"/threads/{thread_id}"),
                    timeout=10000,
                ) as saved:
                    page_a.get_by_role("button", name="保存修改", exact=True).click()
                assert saved.value.status == 200
                edited_response = client_a.get_thread(thread_id)
                _assert_status(edited_response, (200,), "browser thread edit readback")
                edited = _json_body(edited_response, "browser thread edit readback")
                assert int(edited["sectionId"]) == edit_section_id
                record_testsuite_property("thread_editor_section_page", 2)

            context_b = browser.new_context(viewport={"width": 1280, "height": 900}, locale="zh-CN")
            page_b = context_b.new_page()
            _seed_authenticated_page(page_b, token_b, user_b)
            page_b.goto(f"{BASE_URL}/threads/{thread_id}", wait_until="domcontentloaded")
            expect(page_b.get_by_role("heading", name="评论", exact=True)).to_be_visible(timeout=10000)

            comment_content = f"M0 browser comment {uuid4().hex[:12]}"
            page_b.get_by_label("评论内容", exact=True).fill(comment_content)
            page_b.get_by_role("button", name="发送评论", exact=True).click()
            post = _eventually(
                "browser-created comment",
                lambda: _read_post_if_ready(client_a, thread_id, comment_content, user_b_id),
            )
            post_id = int(post["id"])

            # A second interaction notification lets the UI exercise both the
            # explicit "已读" action and the notification "查看" navigation.
            thread_actions_b = page_b.locator("section[aria-label='帖子互动']")
            thread_actions_b.get_by_role("button", name="点赞", exact=True).click()
            like_state = _eventually(
                "browser thread like",
                lambda: _thread_like_state(client_b, thread_id, expected=True),
            )
            assert like_state["liked"] is True

            # Account A performs both bookmark transitions in the browser.
            page_a.goto(f"{BASE_URL}/threads/{thread_id}", wait_until="domcontentloaded")
            bookmark_button = page_a.get_by_role("button", name="收藏", exact=True)
            expect(bookmark_button).to_be_visible(timeout=10000)
            bookmark_button.click()
            expect(page_a.get_by_role("button", name="取消收藏", exact=True)).to_be_visible(timeout=10000)
            bookmarked = _eventually(
                "bookmark creation",
                lambda: _bookmark_state(client_a, thread_id, expected=True),
            )
            assert bookmarked["bookmarked"] is True

            page_a.get_by_role("button", name="取消收藏", exact=True).click()
            expect(page_a.get_by_role("button", name="收藏", exact=True)).to_be_visible(timeout=10000)
            unbookmarked = _eventually(
                "bookmark cancellation",
                lambda: _bookmark_state(client_a, thread_id, expected=False),
            )
            assert unbookmarked["bookmarked"] is False

            # Wait for both asynchronous notifications and read their exact
            # server records before using their titles as UI selectors.
            notifications = _eventually(
                "reply and like notifications",
                lambda: _notification_pair(_read_notifications(client_a), thread_id),
            )
            reply_notification, like_notification = notifications
            if (
                str(reply_notification.get("targetType") or "").lower() != "post"
                or int(reply_notification.get("targetId") or 0) != post_id
            ):
                pytest.fail("reply notification did not target the created comment")

            page_a.get_by_role("button", name="通知", exact=True).click()
            panel = page_a.get_by_role("dialog", name="通知")
            expect(panel).to_be_visible(timeout=10000)

            like_row = panel.locator("li").filter(has_text=str(like_notification.get("title") or "")).first
            expect(like_row).to_be_visible(timeout=10000)
            like_row.get_by_role("button", name="已读", exact=True).click()
            def read_like_if_ready() -> dict[str, Any] | None:
                current = _read_notification_state(client_a, int(like_notification["id"]))
                return current if current and current.get("read") is True else None

            like_read = _eventually("explicit notification read", read_like_if_ready)
            assert like_read["read"] is True

            reply_row = panel.locator("li").filter(has_text=str(reply_notification.get("title") or "")).first
            expect(reply_row).to_be_visible(timeout=10000)
            reply_row.get_by_role("button", name="查看", exact=True).click()
            expect(page_a).to_have_url(
                re.compile(
                    rf"^{re.escape(BASE_URL)}/threads/{thread_id}(?:\?[^#]*)?#post-{post_id}$"
                ),
                timeout=10000,
            )
            comment_locator = page_a.locator(f"#post-{post_id}")
            expect(comment_locator).to_be_visible(timeout=10000)
            expect(comment_locator).to_contain_text(comment_content, timeout=10000)
            def read_reply_if_ready() -> dict[str, Any] | None:
                current = _read_notification_state(client_a, int(reply_notification["id"]))
                return current if current and current.get("read") is True else None

            reply_read = _eventually("notification navigation read", read_reply_if_ready)
            assert reply_read["read"] is True

            # B reports A's thread through the real report dialog.  Capture the
            # network response only to bind the UI action to its server id; the
            # persisted result is verified again through the API replay below.
            report_detail = f"M0 browser report {uuid4().hex[:12]}"
            page_b.goto(f"{BASE_URL}/threads/{thread_id}", wait_until="domcontentloaded")
            expect(thread_actions_b).to_be_visible(timeout=10000)
            thread_report_button = thread_actions_b.get_by_role("button", name="举报", exact=True)
            expect(thread_report_button).to_be_visible(timeout=10000)
            thread_report_button.click()
            report_dialog = page_b.get_by_role("dialog", name="举报内容")
            expect(report_dialog).to_be_visible(timeout=10000)
            report_dialog.locator("#report-reason").select_option("OTHER")
            report_dialog.locator("#report-detail").fill(report_detail)
            with page_b.expect_response(
                lambda response: response.request.method == "POST" and response.url.endswith("/api/v1/reports"),
                timeout=10000,
            ) as report_response_info:
                report_dialog.get_by_role("button", name="提交举报", exact=True).click()
            report_response = report_response_info.value
            if report_response.status != 201:
                pytest.fail(f"browser report submission returned HTTP {report_response.status}")
            report_body = report_response.json()
            if not isinstance(report_body, dict):
                pytest.fail("browser report submission returned an unexpected response shape")
            report_id = int(report_body.get("id") or 0)
            if report_id <= 0:
                pytest.fail("browser report submission did not return a report id")
            if (
                str(report_body.get("targetType") or "").upper() != "THREAD"
                or int(report_body.get("targetId") or 0) != thread_id
                or int(report_body.get("reporterId") or 0) != user_b_id
            ):
                pytest.fail("browser report submission returned mismatched report identity")
            if (
                str(report_body.get("reason") or "").upper() != "OTHER"
                or report_body.get("detail") != report_detail
            ):
                pytest.fail("browser report submission did not preserve submitted report details")
            expect(report_dialog.get_by_role("status")).to_contain_text("举报已提交", timeout=10000)
            _read_report_by_replay(client_b, thread_id, report_detail, user_b_id, report_id)
            _write_fixture(
                owner_id=user_a_id,
                reader_id=user_b_id,
                thread_id=thread_id,
                post_id=post_id,
                report_id=report_id,
                reply_notification_id=int(reply_notification["id"]),
                like_notification_id=int(like_notification["id"]),
            )
            report_dialog.get_by_role("button", name="关闭举报对话框", exact=True).click()
            expect(page_b.get_by_role("dialog", name="举报内容")).to_have_count(0, timeout=5000)

            # A fresh account is not an admin unless the isolated stack has
            # deliberately configured that exact generated email.  Either way,
            # verify the management boundary through the API.
            admin_status = client_b.get_admin_status()
            if admin_status.status_code == 200:
                admin_body = _json_body(admin_status, "admin status")
                admin_payload = _unwrap(admin_body)
                if admin_payload.get("admin") is True:
                    admin_reports = _request(client_b, "GET", "/admin/reports", params={"status": "PENDING", "page": 1, "size": 50})
                    _assert_status(admin_reports, (200,), "admin report readback")
                    report_items = _extract_items(_json_body(admin_reports, "admin report readback"))
                    assert any(int(item.get("id") or 0) == report_id for item in report_items)
                else:
                    # A normal authenticated user may receive HTTP 200 with
                    # ``admin=false``.  Verify the actual management boundary
                    # instead of treating that valid response as a test error.
                    assert admin_payload.get("admin") is False
                    denied_reports = _request(
                        client_b,
                        "GET",
                        "/admin/reports",
                        params={"status": "PENDING", "page": 1, "size": 50},
                    )
                    _assert_status(denied_reports, (403,), "non-admin report boundary")
            else:
                # Account B has already completed a successful authenticated
                # API call, so an admin-status denial must be forbidden rather
                # than an authentication failure.
                _assert_status(admin_status, (403,), "non-admin admin status")

            # Reverse ownership checks are API-only readbacks around browser
            # flows: B cannot mutate A's thread, and A cannot mutate B's post.
            _assert_status(client_b.update_thread(thread_id, {"title": f"forbidden-{uuid4().hex[:8]}"}), (403,), "cross-user thread update")
            _assert_status(client_b.delete_thread(thread_id), (403,), "cross-user thread delete")
            _assert_status(client_a.update_post(post_id, {"contentMd": f"forbidden-{uuid4().hex[:8]}"}), (403,), "cross-user comment update")
            _assert_status(client_a.delete_post(post_id), (403,), "cross-user comment delete")
            assert _read_thread_if_ready(client_a, thread_id, thread_title, user_a_id) is not None
            assert _read_post_if_ready(client_b, thread_id, comment_content, user_b_id) is not None

            # Undo the notification-producing like through its browser context
            # and verify that transition through the API as well.
            unlike_button = thread_actions_b.get_by_role("button", name="取消点赞", exact=True)
            expect(unlike_button).to_have_attribute("aria-pressed", "true")
            unlike_button.click()
            unlike_state = _eventually(
                "browser thread unlike",
                lambda: _thread_like_state(client_b, thread_id, expected=False),
            )
            assert unlike_state["liked"] is False

            if paged_sections:
                _verify_owned_section_filter(
                    page_a,
                    route="/my/threads",
                    endpoint="/api/v1/users/me/threads",
                    section_id=edit_section_id,
                    expected_item_id=thread_id,
                    expected_text=thread_title,
                )
                _verify_owned_section_filter(
                    page_b,
                    route="/my/posts",
                    endpoint="/api/v1/users/me/posts",
                    section_id=edit_section_id,
                    expected_item_id=post_id,
                    expected_text=comment_content,
                )
                record_testsuite_property("owned_section_filter_page", 2)

            if os.getenv("BLUEALBUM_HOME_BFF_ENABLED") == "true":
                _verify_home_overview(page_a, user_a_id)
                record_testsuite_property("home_bff_browser", "personalized_overview_from_real_services")
            _verify_search_history(page_a, thread_title)
            record_testsuite_property("search_history_browser", "submitted_and_cleared")
            _verify_popular_search(page_a, client_a, client_b)
            record_testsuite_property("popular_search_browser", "five_distinct_accounts_required")

            _verify_realtime_chat(page_a, page_b, client_a, client_b, user_a_id, user_b_id, identity_b.username)
            record_testsuite_property("realtime_chat_offline_backlog", 31)
            record_testsuite_property("realtime_chat_group_rejoin_history", "verified")
            record_testsuite_property("friend_request_browser", "accepted_without_mutual_follow")
            record_testsuite_property("chat_message_recall_browser", "receiver_tombstone")
    finally:
        # Keep generated accounts for server-side auditability; clean mutable
        # content/bookmark state where the API provides an owner operation.
        if thread_id is not None:
            try:
                client_a.unbookmark_thread(thread_id)
            except Exception:
                pass
            try:
                client_a.delete_thread(thread_id)
            except Exception:
                pass
        if post_id is not None:
            try:
                client_b.delete_post(post_id)
            except Exception:
                pass
        for context in (context_b, context_a):
            try:
                if context is not None:
                    context.close()
            except Exception:
                pass
        try:
            if browser is not None:
                browser.close()
        except Exception:
            pass
        client_b.close()
        client_a.close()


def _thread_like_state(client: ApiClient, thread_id: int, expected: bool) -> dict[str, Any] | None:
    response = _request(client, "GET", f"/threads/{thread_id}/like")
    if response.status_code != 200:
        return None
    body = _json_body(response, "thread like readback")
    return body if bool(body.get("liked")) is expected else None


def _bookmark_state(client: ApiClient, thread_id: int, expected: bool) -> dict[str, Any] | None:
    response = client.get_thread_bookmark(thread_id)
    if response.status_code != 200:
        return None
    body = _json_body(response, "bookmark readback")
    return body if bool(body.get("bookmarked")) is expected else None


def _notification_pair(
    notifications: list[dict[str, Any]] | None,
    thread_id: int,
) -> tuple[dict[str, Any], dict[str, Any]] | None:
    if notifications is None:
        return None
    matching = [
        item
        for item in notifications
        if int(item.get("threadId") or 0) == thread_id and not bool(item.get("read"))
    ]
    replies = [item for item in matching if str(item.get("type") or "").lower() == "reply"]
    likes = [item for item in matching if str(item.get("type") or "").lower() == "like"]
    if not replies or not likes:
        return None
    return replies[0], likes[0]
