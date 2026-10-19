"""Touch-emulated community tasks; fixtures are not backend or physical-device proof."""

from __future__ import annotations

import pytest
from playwright.sync_api import Browser, Page, expect

from tests.conftest import BASE_URL
from tests.e2e.playwright.test_layout_fixtures import install_fixtures

pytestmark = pytest.mark.ui


@pytest.fixture(params=[(320, 667), (390, 844), (430, 932)])
def phone(request, pw_browser: Browser):
    width, height = request.param
    context = pw_browser.new_context(
        viewport={"width": width, "height": height}, is_mobile=True,
        has_touch=True, device_scale_factor=2, locale="zh-CN", reduced_motion="reduce",
    )
    page = context.new_page()
    yield page
    context.close()


def test_touch_navigation_targets_and_reading_space(phone: Page) -> None:
    install_fixtures(phone)
    phone.goto(BASE_URL + "/discover", wait_until="networkidle")
    nav = phone.get_by_role("navigation", name="移动端导航")
    links = nav.get_by_role("link")
    expect(links).to_have_count(5)
    for link in links.all():
        box = link.bounding_box()
        assert box and box["width"] >= 48 and box["height"] >= 48
        assert box["y"] >= phone.viewport_size["height"] - 80
    for label in ("搜索", "通知"):
        button = phone.locator(".community-header").get_by_role("button", name=label, exact=True).first
        box = button.bounding_box()
        assert box and box["width"] >= 48 and box["height"] >= 48
    expect(phone.locator(".community-header").get_by_role("button", name="登出")).to_be_hidden()
    expect(phone.get_by_role("button", name="找小祥聊天")).to_be_hidden()
    nav.get_by_role("link", name="我的", exact=True).tap()
    expect(phone).to_have_url(BASE_URL + "/me")
    expect(nav.get_by_role("link", name="我的", exact=True)).to_have_attribute("aria-current", "page")


def test_touch_composer_focus_preview_and_publish_feedback(phone: Page) -> None:
    install_fixtures(phone)
    phone.goto(BASE_URL + "/discover", wait_until="networkidle")
    phone.locator(".mobile-create").tap()
    expect(phone).to_have_url(BASE_URL + "/threads/new")
    editor = phone.locator("textarea").first
    expect(editor).to_be_visible()
    expect(phone.get_by_role("button", name="返回编辑", exact=True)).to_be_hidden()
    box = editor.bounding_box()
    assert box and box["y"] < phone.viewport_size["height"] - 80
    editor.fill("手机触屏撰写的正文")
    nav = phone.get_by_role("navigation", name="移动端导航")
    expect(nav).to_be_hidden()
    phone.get_by_role("button", name="正文预览", exact=True).tap()
    expect(editor).to_be_hidden()
    expect(phone.locator(".prose")).to_contain_text("手机触屏撰写的正文")
    phone.get_by_role("button", name="返回编辑", exact=True).tap()
    expect(editor).to_have_value("手机触屏撰写的正文")
    phone.get_by_role("button", name="发布", exact=True).filter(visible=True).tap()
    error = phone.get_by_text("请完整填写分区、标题与内容", exact=True).filter(visible=True)
    expect(error).to_be_visible()
    error_box = error.bounding_box()
    assert error_box and error_box["y"] < phone.viewport_size["height"]


def test_long_thread_reply_remains_reachable(phone: Page) -> None:
    install_fixtures(phone)
    phone.goto(BASE_URL + "/threads/41", wait_until="networkidle")
    phone.get_by_role("button", name="查看评论", exact=True).tap()
    expect(phone.locator("#thread-comments")).to_be_in_viewport()
    expect(phone).to_have_url(BASE_URL + "/threads/41")
    phone.evaluate("window.scrollTo(0, 1200)")
    reply = phone.get_by_role("button", name="写回复", exact=True)
    expect(reply).to_be_in_viewport()
    reply.tap()
    editor = phone.locator("#thread-comments textarea").first
    expect(editor).to_be_focused()
    expect(editor).to_be_in_viewport()
    expect(phone.get_by_role("navigation", name="移动端导航")).to_be_hidden()
    expect(reply).to_be_hidden()


def test_guest_reply_opens_login(phone: Page) -> None:
    install_fixtures(phone, authenticated=False)
    phone.goto(BASE_URL + "/threads/41", wait_until="networkidle")
    phone.get_by_role("button", name="写回复", exact=True).tap()
    expect(phone.get_by_role("dialog")).to_be_visible()
    expect(phone.get_by_role("dialog").get_by_role("button", name="关闭", exact=True)).to_be_visible()


def test_account_theme_chat_and_logout_cancel(phone: Page) -> None:
    install_fixtures(phone)
    phone.goto(BASE_URL + "/me", wait_until="networkidle")
    phone.get_by_role("button", name="切换为黑夜模式", exact=True).tap()
    expect(phone.locator("html")).to_have_class("dark")
    assert phone.evaluate("localStorage.getItem('theme')") == "dark"
    phone.get_by_role("button", name="AI 助手", exact=False).tap()
    expect(phone.get_by_role("dialog", name="小祥聊天")).to_be_visible()
    phone.get_by_role("button", name="关闭聊天", exact=True).tap()
    trigger = phone.get_by_role("button", name="退出登录", exact=True)
    trigger.tap()
    expect(phone.get_by_role("dialog")).to_be_visible()
    phone.get_by_role("button", name="取消", exact=True).tap()
    expect(phone.get_by_role("dialog")).to_be_hidden()
    assert phone.evaluate("localStorage.getItem('accessToken')") == "ui-fixture-token"


def test_phone_landscape_keeps_compact_navigation(phone: Page) -> None:
    install_fixtures(phone)
    phone.set_viewport_size({"width": phone.viewport_size["height"], "height": phone.viewport_size["width"]})
    phone.goto(BASE_URL + "/discover", wait_until="networkidle")
    expect(phone.get_by_role("navigation", name="移动端导航")).to_be_visible()
    expect(phone.locator(".community-header").get_by_role("button", name="登出")).to_be_hidden()
    expect(phone.locator(".mobile-create")).to_be_visible()
    assert phone.evaluate("document.documentElement.scrollWidth <= innerWidth + 1")


def test_reading_back_preserves_source_filter(phone: Page) -> None:
    install_fixtures(phone)
    phone.goto(BASE_URL + "/discover?q=community", wait_until="networkidle")
    phone.locator('main a[href="/threads/41"]').first.tap()
    expect(phone).to_have_url(BASE_URL + "/threads/41")
    phone.evaluate("window.scrollTo(0, 1100)")
    back = phone.locator(".community-header").get_by_role("button", name="返回上一页", exact=True)
    expect(back).to_be_in_viewport()
    back.tap()
    expect(phone).to_have_url(BASE_URL + "/discover?q=community")
