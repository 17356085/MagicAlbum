"""Frontend layout/keyboard checks with intercepted fixtures; no backend acceptance."""

from __future__ import annotations

import json
import os
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import pytest
from playwright.sync_api import Page, Route, expect

from tests.conftest import BASE_URL


LONG_TITLE = "一起分享最近的阅读与编程心得" + "LongUnbrokenCommunityTitle" * 12
USER = {"id": 901, "username": "community-member", "nickname": "非常长的社区成员昵称" * 5}
THREAD = {
    "id": 41, "title": LONG_TITLE, "content": "社区里的长内容测试。", "authorId": 901,
    "authorUsername": USER["username"], "authorNickname": USER["nickname"],
    "sectionId": 3, "sectionName": "编程", "replyCount": 0, "viewCount": 24,
    "likeCount": 2, "createdAt": "2026-10-18T08:00:00Z", "tags": ["编程"],
}


def install_fixtures(page: Page, authenticated: bool = True, dark: bool = False) -> None:
    page.add_init_script("localStorage.setItem('theme', %r)" % ("dark" if dark else "light"))
    if authenticated:
        page.add_init_script(
            "localStorage.setItem('accessToken', 'ui-fixture-token');"
            "localStorage.setItem('currentUser', JSON.stringify(%s));" % json.dumps(USER)
        )

    def respond(route: Route) -> None:
        parsed = urlparse(route.request.url)
        endpoint = parsed.path.split("/api/v1", 1)[-1]
        query = parse_qs(parsed.query)
        size = int(query.get("size", ["20"])[0])
        payload = {"items": [], "page": 1, "size": size, "total": 0}
        status = 200
        if endpoint == "/sections":
            payload.update(items=[{"id": 3, "name": "编程", "slug": "coding"}], total=1)
        elif endpoint in ("/threads", "/threads/ranking"):
            payload.update(items=[THREAD], total=1)
        elif endpoint == "/threads/41":
            payload = {**THREAD, "content": ("手机阅读时，正文需要有清晰的行距和稳定的操作位置。\n\n" * 35)}
        elif endpoint == "/threads/41/like":
            payload = {"liked": False, "likeCount": 2, "threadId": 41}
        elif endpoint == "/threads/41/bookmark":
            payload = {"bookmarked": False}
        elif endpoint == "/ai/summary/41":
            payload = {"summary": "移动端阅读体验测试。", "status": "COMPLETED"}
        elif endpoint == "/threads/41/posts":
            pass
        elif endpoint == "/users/me" or endpoint == "/users/901/profile":
            payload = {**USER, "bio": "", "homepageUrl": "", "location": "", "avatarUrl": ""}
        elif endpoint == "/admin/me":
            status = 403
        elif endpoint == "/notifications":
            payload.update(unreadCount=0)
        elif endpoint not in ("/tags/popular",):
            status = 404
        route.fulfill(status=status, json=payload)

    page.route("**/api/v1/**", respond)
    page.route("https://api.dicebear.com/**", lambda route: route.fulfill(
        content_type="image/svg+xml",
        body='<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32"><rect width="32" height="32" fill="#1d4ed8"/></svg>',
    ))


def assert_header_fits(page: Page, width: int) -> None:
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1")
    for control in page.locator("header button:visible, header a:visible, header input:visible").all():
        box = control.bounding_box()
        assert box and box["x"] >= -1 and box["x"] + box["width"] <= width + 1, control.inner_text()


@pytest.mark.ui
@pytest.mark.parametrize("width", (320, 390, 768, 1024, 1280))
@pytest.mark.parametrize("dark", (False, True))
@pytest.mark.parametrize("path", ("/discover", "/ranking", "/threads/new"))
def test_content_and_header_fit(page: Page, width: int, dark: bool, path: str) -> None:
    page.set_viewport_size({"width": width, "height": 900})
    install_fixtures(page, dark=dark)
    page.goto(BASE_URL + path, wait_until="networkidle")
    if path != "/threads/new":
        expect(page.locator("main").get_by_text(LONG_TITLE, exact=True)).to_be_visible()
    else:
        expect(page.locator("textarea").first).to_be_visible()
    output = os.getenv("TEST_LAYOUT_ARTIFACTS")
    if output:
        target = Path(output)
        target.mkdir(parents=True, exist_ok=True)
        page.screenshot(path=str(target / f"{path.strip('/').replace('/', '-')}-{width}-{'dark' if dark else 'light'}.png"), full_page=True)
    assert_header_fits(page, width)


@pytest.mark.ui
@pytest.mark.parametrize("width", (320, 390, 1280))
def test_optional_tags_are_entered_through_visible_disclosure(page: Page, width: int) -> None:
    page.set_viewport_size({"width": width, "height": 900})
    install_fixtures(page)
    page.goto(BASE_URL + "/threads/new", wait_until="networkidle")
    tags = page.get_by_label("标签", exact=True)
    expect(tags).to_be_hidden()
    page.get_by_text("标签（可选）", exact=True).click()
    expect(tags).to_be_visible()
    tags.fill("社区, 阅读")
    expect(tags).to_have_value("社区, 阅读")


@pytest.mark.ui
@pytest.mark.parametrize("width", (320, 390))
def test_mobile_search_escape_restores_trigger(page: Page, width: int) -> None:
    page.set_viewport_size({"width": width, "height": 844})
    install_fixtures(page)
    page.goto(BASE_URL + "/discover", wait_until="networkidle")
    trigger = page.locator('button[aria-controls="header-search-panel"]')
    trigger.click()
    search = page.get_by_role("textbox", name="搜索关键词")
    expect(search).to_be_focused()
    search.press("Escape")
    expect(search).to_be_hidden()
    expect(trigger).to_be_focused()


@pytest.mark.ui
@pytest.mark.parametrize("width", (320, 390))
def test_editor_link_keyboard_and_route_leave_draft(page: Page, width: int) -> None:
    page.set_viewport_size({"width": width, "height": 844})
    install_fixtures(page)
    page.goto(BASE_URL + "/threads/new", wait_until="networkidle")
    editor = page.locator("textarea").first
    editor.fill("最后的正文")
    page.get_by_role("button", name="链接", exact=True).click()
    label = page.get_by_placeholder("请输入链接文本")
    expect(label).to_be_focused()
    label.press("Escape")
    expect(label).to_be_hidden()
    expect(editor).to_be_focused()
    page.get_by_role("button", name="链接", exact=True).click()
    label.fill("社区")
    page.get_by_label("链接地址", exact=True).fill("https://example.com/community")
    page.get_by_label("链接地址", exact=True).press("Enter")
    expect(editor).to_have_value("最后的正文[社区](https://example.com/community)")
    expect(editor).to_be_focused()
    page.get_by_role("heading", name="发帖", exact=True).click()
    page.get_by_role("navigation", name="移动端导航").get_by_role("link", name="发现", exact=True).click()
    expect(page).to_have_url(BASE_URL + "/discover")
    saved = page.evaluate("JSON.parse(localStorage.getItem('thread-draft-v1:901'))")
    assert saved["content"] == "最后的正文[社区](https://example.com/community)"


@pytest.mark.ui
@pytest.mark.parametrize("width", (320, 390))
def test_login_dialog_keyboard_focus_and_escape(page: Page, width: int) -> None:
    page.set_viewport_size({"width": width, "height": 844})
    install_fixtures(page, authenticated=False)
    page.goto(BASE_URL + "/me", wait_until="networkidle")
    trigger = page.get_by_role("button", name="登录", exact=True)
    assert_header_fits(page, width)
    trigger.click()
    dialog = page.get_by_role("dialog")
    expect(dialog).to_be_visible()
    expect(dialog).to_be_focused()
    page.keyboard.press("Shift+Tab")
    assert dialog.evaluate("el => el.contains(document.activeElement)")
    page.keyboard.press("Tab")
    assert dialog.evaluate("el => el.contains(document.activeElement)")
    page.keyboard.press("Escape")
    expect(dialog).to_be_hidden()
    expect(trigger).to_be_focused()
