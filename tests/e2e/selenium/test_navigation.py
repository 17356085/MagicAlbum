"""
导航和路由测试 — Selenium 版本

Selenium 学习要点 — 页面导航测试：
  测试 Vue Router 的导航功能：
  - 侧边栏链接跳转
  - 品牌 Logo 点击回首页
  - 路由守卫拦截
  - 浏览器前进/后退
"""

import pytest
from selenium.webdriver.remote.webdriver import WebDriver

from tests.e2e.selenium.pages.auth_modal_page import AuthModalPage
from tests.e2e.selenium.pages.discover_page import DiscoverPage
from tests.e2e.selenium.config.settings import settings


class TestBasicNavigation:
    """基础导航测试"""

    @pytest.mark.smoke
    @pytest.mark.ui
    def test_navigate_to_discover(self, clean_page: WebDriver) -> None:
        """
        直接访问发现页
        验证：页面加载，URL 正确
        """
        discover = DiscoverPage(clean_page)
        discover.go()
        discover.wait_for_content()

        assert "/discover" in clean_page.current_url, (
            f"URL 应包含 /discover，实际: {clean_page.current_url}"
        )

    @pytest.mark.ui
    def test_navigate_to_ranking(self, clean_page: WebDriver) -> None:
        """
        访问排行榜页
        验证：页面正常加载
        """
        clean_page.get(f"{settings.BASE_URL}/ranking")
        import time
        time.sleep(1)

        # 页面应该正常加载
        assert clean_page.current_url and "/ranking" in clean_page.current_url

    @pytest.mark.ui
    def test_navigate_to_sections(self, clean_page: WebDriver) -> None:
        """
        访问分区列表页
        验证：页面正常加载
        """
        clean_page.get(f"{settings.BASE_URL}/sections")
        import time
        time.sleep(1)

        assert clean_page.current_url and "/sections" in clean_page.current_url


class TestRouteGuard:
    """路由守卫测试 — 未登录拦截"""

    @pytest.mark.p0
    @pytest.mark.ui
    def test_unauthenticated_thread_create(self, clean_page: WebDriver) -> None:
        """
        未登录访问 /threads/new
        验证：弹出登录弹窗（路由守卫阻止导航）
        """
        # 先到发现页建立上下文
        discover = DiscoverPage(clean_page)
        discover.go()
        discover.wait_for_content()

        # 尝试跳转到受保护路由
        clean_page.get(f"{settings.BASE_URL}/threads/new")
        import time
        time.sleep(1.5)

        auth = AuthModalPage(clean_page)
        on_discover = "/discover" in clean_page.current_url
        has_login = "login" in clean_page.current_url

        assert auth.is_visible() or on_discover or has_login, (
            f"未登录访问应弹窗或重定向并带login参数。弹窗={auth.is_visible()}, "
            f"当前URL: {clean_page.current_url}"
        )

    @pytest.mark.p0
    @pytest.mark.ui
    def test_unauthenticated_feed(self, clean_page: WebDriver) -> None:
        """
        未登录访问 /feed
        验证：弹窗或重定向
        """
        clean_page.get(f"{settings.BASE_URL}/feed")
        import time
        time.sleep(1.5)

        auth = AuthModalPage(clean_page)
        on_discover = "/discover" in clean_page.current_url
        modal_visible = auth.is_visible()

        assert on_discover or modal_visible, (
            f"应重定向或弹窗(discover={on_discover}, modal={modal_visible})"
        )

    @pytest.mark.p1
    @pytest.mark.ui
    def test_unauthenticated_settings(self, clean_page: WebDriver) -> None:
        """
        未登录访问 /settings
        验证：弹出登录弹窗
        """
        clean_page.get(f"{settings.BASE_URL}/settings")
        import time
        time.sleep(1)

        auth = AuthModalPage(clean_page)
        on_discover = "/discover" in clean_page.current_url
        has_login = "login" in clean_page.current_url
        assert auth.is_visible() or on_discover or has_login, (
            f"未登录访问设置页应弹窗或重定向。当前URL: {clean_page.current_url}"
        )


class TestBrowserNavigation:
    """浏览器前进/后退测试"""

    @pytest.mark.ui
    def test_browser_back_button(self, clean_page: WebDriver) -> None:
        """
        浏览器的前进/后退功能

        Selenium 学习要点 — 浏览器导航：
          driver.back()  — 后退
          driver.forward() — 前进
          driver.refresh() — 刷新

          在 Vue SPA 中，前进/后退触发 Vue Router 的 popstate 事件。
        """
        discover = DiscoverPage(clean_page)
        discover.go()
        discover.wait_for_content()

        # 跳转到排行榜
        clean_page.get(f"{settings.BASE_URL}/ranking")
        import time
        time.sleep(1)
        assert "/ranking" in clean_page.current_url

        # 后退
        clean_page.back()
        time.sleep(1)
        assert "/discover" in clean_page.current_url, (
            f"后退应回到发现页，实际: {clean_page.current_url}"
        )

        # 前进
        clean_page.forward()
        time.sleep(1)
        assert "/ranking" in clean_page.current_url, (
            f"前进应到排行榜，实际: {clean_page.current_url}"
        )
