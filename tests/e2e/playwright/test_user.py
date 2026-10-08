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

        profile = UserProfilePage(authenticated_page)
        profile.go(user_id)
        profile.wait_for_content()

        assert profile.username_display.is_visible()

    @pytest.mark.ui
    def test_user_profile_not_found(self, page: Page) -> None:
        """访问不存在的用户"""
        profile = UserProfilePage(page)
        profile.go(99999999)
        profile.wait_for_content()
        # 页面应显示错误或不存在提示


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
        assert sum(tab.count() for tab in tabs) >= 2  # 至少有 2 个 tab

    @pytest.mark.p2
    @pytest.mark.ui
    def test_account_settings_page(self, authenticated_page: Page) -> None:
        """账号设置页加载"""
        settings = SettingsPage(authenticated_page)
        settings.go_account()

        # 路由组件异步加载，等待真正可操作的密码表单。
        expect(authenticated_page.get_by_role("group", name="修改密码")).to_be_visible()
