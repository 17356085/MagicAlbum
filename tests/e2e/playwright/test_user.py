"""
用户模块测试

覆盖: 用户搜索、个人主页、关注/取关、设置修改
"""

import pytest
from playwright.sync_api import Page, expect

from tests.e2e.playwright.pages.settings_page import SettingsPage
from tests.e2e.playwright.pages.user_profile_page import UserProfilePage


class TestUserProfile:
    """用户主页测试"""

    @pytest.mark.p1
    @pytest.mark.ui
    def test_user_profile_loads(
        self, authenticated_page: Page, auth_session: dict[str, object]
    ) -> None:
        """用户主页加载（查看自己的主页）"""
        user_data = auth_session["user"]
        user_id = user_data.get("id") or user_data.get("userId")
        expected_username = str(user_data.get("username") or "").strip()
        assert user_id is not None
        assert expected_username

        profile = UserProfilePage(authenticated_page)
        with authenticated_page.expect_response(
            lambda response: (
                response.request.method == "GET"
                and response.url.split("?", 1)[0].rstrip("/").endswith(
                    f"/users/{user_id}/profile"
                )
            ),
            timeout=15000,
        ) as profile_response_info:
            profile.go(user_id)

        profile_response = profile_response_info.value
        assert profile_response.status == 200
        body = profile_response.json()
        payload = body.get("data") if isinstance(body, dict) else None
        if not isinstance(payload, dict):
            payload = body
        assert isinstance(payload, dict)
        assert str(payload.get("id") or payload.get("userId")) == str(user_id)
        assert str(payload.get("username") or "").strip() == expected_username

        expected_nickname = str(payload.get("nickname") or "").strip()
        expected_display_name = expected_nickname or expected_username

        expect(profile.username_display).to_have_text(expected_username)
        expect(profile.nickname_display).to_have_text(
            expected_display_name
        )

    @pytest.mark.ui
    def test_user_profile_not_found(self, page: Page) -> None:
        """访问不存在的用户"""
        profile = UserProfilePage(page)
        missing_user_id = 99999999
        with page.expect_response(
            lambda response: (
                response.request.method == "GET"
                and response.url.split("?", 1)[0].rstrip("/").endswith(
                    f"/users/{missing_user_id}/profile"
                )
            ),
            timeout=15000,
        ) as profile_response_info:
            profile.go(missing_user_id)

        profile_response = profile_response_info.value
        assert profile_response.status == 404
        profile.wait_for_content()
        expect(profile.error_alert).to_be_visible()
        assert (profile.error_alert.text_content() or "").strip()


class TestSettings:
    """设置页测试"""

    @pytest.mark.p1
    @pytest.mark.ui
    def test_settings_page_loads(self, authenticated_page: Page) -> None:
        """设置页正常加载"""
        settings = SettingsPage(authenticated_page)
        settings.go()

        settings.profile_tab.wait_for(state="visible", timeout=10000)

    @pytest.mark.p1
    @pytest.mark.ui
    def test_settings_tabs_exist(self, authenticated_page: Page) -> None:
        """设置页包含所有Tab"""
        settings = SettingsPage(authenticated_page)
        settings.go()

        # 检查当前 Settings.vue 中的 tab 按钮存在
        tabs = (
            settings.profile_tab,
            settings.notifications_tab,
            settings.connected_tab,
            settings.account_link,
        )
        for tab in tabs:
            expect(tab).to_have_count(1)
            expect(tab).to_be_visible()

        settings.switch_to_profile()
        expect(settings.profile_panel).to_be_visible()
        settings.switch_to_notifications()
        expect(settings.notifications_panel).to_be_visible()
        settings.switch_to_connected()
        expect(settings.connected_panel).to_be_visible()
        settings.account_link.click()
        expect(settings.account_panel).to_be_visible()

    @pytest.mark.p2
    @pytest.mark.ui
    def test_account_settings_page(self, authenticated_page: Page) -> None:
        """账号设置页加载"""
        settings = SettingsPage(authenticated_page)
        settings.go_account()

        # 路由组件异步加载，等待真正可操作的密码表单。
        expect(authenticated_page.get_by_role("group", name="修改密码")).to_be_visible()
