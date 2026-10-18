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
from uuid import uuid4

import pytest
from playwright.sync_api import Browser, BrowserContext, Page, expect, sync_playwright

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
    if str(body.get("status") or "").upper() != "PENDING" or body.get("detail") != detail:
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
def test_m0_r01_r04_browser_journey() -> None:
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
        with sync_playwright() as playwright, _CaptureOnFailure(
            lambda: [page for page in (page_a, page_b) if page is not None]
        ):
            channel = os.getenv("TEST_PLAYWRIGHT_CHANNEL", "chrome").strip() or None
            browser = playwright.chromium.launch(
                channel=channel,
                headless=True,
                args=["--no-sandbox", "--disable-setuid-sandbox"],
            )
            context_a = browser.new_context(viewport={"width": 1280, "height": 900}, locale="zh-CN")
            page_a = context_a.new_page()

            # Account A is created and authenticated through the browser UI.
            _register_and_login_ui(page_a, identity_a)
            _, user_a_id, user_a = _login_via_api(client_a, identity_a)

            sections = client_a.list_sections(page=1, size=1)
            _assert_status(sections, (200,), "section lookup")
            section_items = _extract_items(_json_body(sections, "section lookup"))
            if not section_items or _extract_id(section_items[0]) is None:
                pytest.fail("isolated stack did not expose a writable section")
            section_id = int(section_items[0]["id"])

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
            thread_actions_b.get_by_role("button", name="点赞", exact=False).click()
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
            page_b.get_by_role("button", name="举报", exact=True).first.click()
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
            report_id = int(report_body.get("id") or 0) if isinstance(report_body, dict) else 0
            if report_id <= 0:
                pytest.fail("browser report submission did not return a report id")
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
                admin_reports = _request(client_b, "GET", "/admin/reports", params={"status": "PENDING", "page": 1, "size": 50})
                _assert_status(admin_reports, (200,), "admin report readback")
                report_items = _extract_items(_json_body(admin_reports, "admin report readback"))
                assert any(int(item.get("id") or 0) == report_id for item in report_items)
            else:
                assert admin_status.status_code in (401, 403)

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
            thread_actions_b.get_by_role("button", name="已点赞", exact=False).click()
            unlike_state = _eventually(
                "browser thread unlike",
                lambda: _thread_like_state(client_b, thread_id, expected=False),
            )
            assert unlike_state["liked"] is False
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
