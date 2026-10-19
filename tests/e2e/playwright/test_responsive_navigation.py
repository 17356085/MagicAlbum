"""Responsive header entry points and notification popover behavior."""

from __future__ import annotations

import re

import pytest
from playwright.sync_api import Page, expect

from tests.conftest import BASE_URL


MOBILE_VIEWPORTS = ((320, 640), (390, 844))


def _assert_no_horizontal_overflow(page: Page) -> None:
    assert page.evaluate(
        """() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1"""
    ), "页面在当前视口出现横向溢出"


@pytest.mark.p1
@pytest.mark.ui
@pytest.mark.parametrize("width,height", (*MOBILE_VIEWPORTS, (768, 900)))
def test_header_entry_points_fit_at_required_viewports(page: Page, width: int, height: int) -> None:
    """登录、发帖、通知和搜索入口在验收视口中均可见。"""
    page.set_viewport_size({"width": width, "height": height})
    page.goto(f"{BASE_URL}/discover", wait_until="domcontentloaded")

    header = page.locator("header").first
    expect(header).to_be_visible()
    expect(page.get_by_role("button", name="通知")).to_be_visible()

    if width < 1024:
        expect(header.get_by_role("button", name="登录")).to_be_hidden()
        expect(page.get_by_role("navigation", name="移动端导航").get_by_role("link", name="我的", exact=True)).to_be_visible()
        expect(page.locator(".mobile-create")).to_be_visible()
        search_trigger = header.locator('button[aria-label="搜索"]:visible').first
        expect(search_trigger).to_be_visible()
        search_trigger.click()
        search_input = header.locator('input[placeholder="搜索帖子标题或内容"]:visible')
        expect(search_input).to_be_visible()
    else:
        expect(page.get_by_role("button", name="登录")).to_be_visible()
        expect(header.get_by_role("link", name="发帖")).to_be_visible()
        search_input = header.locator('input[placeholder="搜索帖子标题或内容"]:visible')
        expect(search_input).to_be_visible()

    search_input.fill("responsive")
    search_input.press("Enter")
    expect(page).to_have_url(re.compile(r"/discover\?.*q=responsive"))

    _assert_no_horizontal_overflow(page)


@pytest.mark.p1
@pytest.mark.ui
@pytest.mark.parametrize("width,height", MOBILE_VIEWPORTS)
def test_mobile_navigation_links_are_reachable(page: Page, width: int, height: int) -> None:
    """移动底栏提供五个稳定的页面入口。"""
    page.set_viewport_size({"width": width, "height": height})
    page.goto(f"{BASE_URL}/discover", wait_until="domcontentloaded")

    mobile_nav = page.get_by_role("navigation", name="移动端导航")
    expect(mobile_nav).to_be_visible()
    for label in ("发现", "分区", "排行", "动态", "我的"):
        expect(mobile_nav.get_by_role("link", name=label)).to_be_visible()

    mobile_nav.get_by_role("link", name="分区").click()
    expect(page).to_have_url(re.compile(r"/sections/?$"))
    page.goto(f"{BASE_URL}/discover", wait_until="domcontentloaded")

    feed_link = mobile_nav.get_by_role("link", name="动态", exact=True)
    expect(feed_link).to_have_attribute("href", "/feed")
    _assert_no_horizontal_overflow(page)


@pytest.mark.p1
@pytest.mark.ui
@pytest.mark.parametrize("width,height", MOBILE_VIEWPORTS)
def test_notification_popover_closes_with_escape_and_outside_click(page: Page, auth_session: dict, width: int, height: int) -> None:
    """通知弹层提供 aria 状态，Esc 回焦点，点击外部关闭。"""
    page.set_viewport_size({"width": width, "height": height})
    page.goto(BASE_URL, wait_until="domcontentloaded")
    page.evaluate(
        """({token, user}) => {
            localStorage.setItem('accessToken', token)
            localStorage.setItem('currentUser', JSON.stringify(user))
        }""",
        {"token": auth_session["access_token"], "user": auth_session["user"]},
    )
    page.goto(f"{BASE_URL}/discover", wait_until="domcontentloaded")

    button = page.get_by_role("button", name="通知")
    expect(button).to_have_attribute("aria-controls", "header-notifications-panel")
    expect(button).to_have_attribute("aria-expanded", "false")
    button.click()

    panel = page.locator("#header-notifications-panel")
    expect(panel).to_be_visible()
    expect(button).to_have_attribute("aria-expanded", "true")
    panel_box = panel.bounding_box()
    assert panel_box is not None
    assert panel_box["x"] >= 0
    assert panel_box["x"] + panel_box["width"] <= width
    assert panel_box["y"] >= 0
    assert panel_box["y"] + panel_box["height"] <= height
    page.keyboard.press("Escape")
    expect(panel).to_be_hidden()
    expect(button).to_be_focused()

    button.click()
    expect(panel).to_be_visible()
    page.mouse.click(8, min(height - 8, 300))
    expect(panel).to_be_hidden()
    expect(button).to_have_attribute("aria-expanded", "false")
