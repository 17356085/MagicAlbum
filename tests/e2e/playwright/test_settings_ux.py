"""Settings navigation, draft protection, and narrow-screen affordances."""

import os
from pathlib import Path

import pytest
from playwright.sync_api import Page, expect


BASE_URL = os.getenv("TEST_BASE_URL", "http://127.0.0.1:5173").rstrip("/")


@pytest.mark.ui
@pytest.mark.parametrize("width", [320, 390, 768])
def test_settings_navigation_is_visible_at_mobile_widths(
    authenticated_page: Page, width: int
) -> None:
    page = authenticated_page
    page.set_viewport_size({"width": width, "height": 800})
    page.goto(f"{BASE_URL}/settings?tab=profile")

    for label in ("资料设置", "通知管理", "第三方关联", "账号信息"):
        tab = page.get_by_role("button", name=label, exact=True)
        expect(tab).to_be_visible()
        bounds = tab.bounding_box()
        assert bounds is not None
        assert bounds["x"] >= 0
        assert bounds["x"] + bounds["width"] <= width

    if os.getenv("CAPTURE_SETTINGS_SCREENSHOTS"):
        output = Path(__file__).resolve().parents[3] / ".tmp" / "settings-review"
        output.mkdir(parents=True, exist_ok=True)
        page.screenshot(path=str(output / f"profile-{width}-after.png"), full_page=True)

    page.get_by_role("button", name="账号信息", exact=True).click()
    expect(page).to_have_url(f"{BASE_URL}/settings?tab=account")
    expect(page.get_by_label("当前密码")).to_be_visible()
    if width < 1024:
        assert page.locator(".settings-mobile-hidden").count() == 1
        expect(page.locator(".settings-mobile-hidden")).to_be_hidden()
    if os.getenv("CAPTURE_SETTINGS_SCREENSHOTS"):
        output = Path(__file__).resolve().parents[3] / ".tmp" / "settings-review"
        output.mkdir(parents=True, exist_ok=True)
        page.screenshot(path=str(output / f"account-{width}-after.png"), full_page=True)


@pytest.mark.ui
def test_settings_unsaved_draft_blocks_navigation_and_keeps_url(
    authenticated_page: Page,
) -> None:
    page = authenticated_page
    page.goto(f"{BASE_URL}/settings?tab=profile")
    nickname = page.get_by_label("昵称", exact=True)
    expect(nickname).to_be_enabled()
    original = nickname.input_value()
    nickname.fill(f"{original} 临时草稿")

    page.once("dialog", lambda dialog: dialog.dismiss())
    page.get_by_role("button", name="通知管理", exact=True).click()
    expect(page).to_have_url(f"{BASE_URL}/settings?tab=profile")
    expect(nickname).to_have_value(f"{original} 临时草稿")

    page.once("dialog", lambda dialog: dialog.accept())
    page.get_by_role("button", name="通知管理", exact=True).click()
    expect(page).to_have_url(f"{BASE_URL}/settings?tab=notifications")
    page.get_by_role("button", name="资料设置", exact=True).click()
    expect(nickname).to_have_value(original)


@pytest.mark.ui
def test_legacy_account_address_uses_guarded_settings_tab(
    authenticated_page: Page,
) -> None:
    page = authenticated_page
    page.goto(f"{BASE_URL}/settings/account")
    expect(page).to_have_url(f"{BASE_URL}/settings?tab=account")
    current_password = page.get_by_label("当前密码")
    current_password.fill("unsaved-local-test")

    page.once("dialog", lambda dialog: dialog.dismiss())
    page.get_by_role("button", name="资料设置", exact=True).click()
    expect(page).to_have_url(f"{BASE_URL}/settings?tab=account")
    expect(current_password).to_have_value("unsaved-local-test")

    page.once("dialog", lambda dialog: dialog.accept())
    page.get_by_role("button", name="资料设置", exact=True).click()
    expect(page).to_have_url(f"{BASE_URL}/settings?tab=profile")


@pytest.mark.ui
def test_notification_preference_can_be_saved_and_reloaded(
    authenticated_page: Page,
) -> None:
    page = authenticated_page
    page.goto(f"{BASE_URL}/settings?tab=notifications")
    reply = page.get_by_label("回复通知")
    expect(reply).to_be_enabled()
    before = reply.is_checked()

    try:
        reply.set_checked(not before)
        page.get_by_role("button", name="保存通知设置").click()
        expect(page.get_by_text("保存成功")).to_be_visible()
        page.reload()
        expect(reply).to_be_checked() if not before else expect(reply).not_to_be_checked()
    finally:
        reply.set_checked(before)
        page.get_by_role("button", name="保存通知设置").click()
        expect(page.get_by_text("保存成功")).to_be_visible()
