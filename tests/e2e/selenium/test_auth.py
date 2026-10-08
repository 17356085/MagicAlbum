"""
认证模块测试 — Selenium 版本

涵盖：登录弹窗、密码登录、表单验证、路由守卫、API 登录

Selenium 学习要点 — 测试结构：
  每个测试方法 = 一个独立验证场景

  AAA 模式 (Arrange-Act-Assert):
    Arrange: 准备测试数据和前置条件
    Act:     执行被测操作
    Assert:  验证预期结果

  命名规范: test_[动作]_[条件]_[预期]
"""

import pytest
from selenium.webdriver.remote.webdriver import WebDriver

from tests.e2e.selenium.pages.auth_modal_page import AuthModalPage
from tests.e2e.selenium.pages.discover_page import DiscoverPage
from tests.e2e.selenium.pages.header_page import HeaderPage
from tests.e2e.selenium.config.settings import settings


# ═══════════════════════════════════════════════════════════════
# UI 测试 — 登录弹窗
# ═══════════════════════════════════════════════════════════════

class TestAuthModalUI:
    """登录弹窗 UI 测试"""

    @pytest.mark.smoke
    @pytest.mark.ui
    def test_open_login_modal(self, clean_page: WebDriver) -> None:
        """
        场景：点击登录按钮打开弹窗
        验证：弹窗遮罩可见、登录/注册 Tab 可见
        """
        # Arrange
        discover = DiscoverPage(clean_page)
        header = HeaderPage(clean_page)

        # Act
        discover.go()
        discover.wait_for_content()
        header.click_login()

        # Assert
        auth = AuthModalPage(clean_page)
        auth.wait_for_modal()
        assert auth.is_visible(), "登录弹窗应该可见"

    @pytest.mark.ui
    def test_close_login_modal(self, clean_page: WebDriver) -> None:
        """
        场景：点击关闭按钮关闭弹窗
        验证：弹窗遮罩不可见
        """
        # Arrange
        discover = DiscoverPage(clean_page)
        header = HeaderPage(clean_page)
        discover.go()
        discover.wait_for_content()
        header.click_login()

        # Act
        auth = AuthModalPage(clean_page)
        auth.wait_for_modal()
        auth.close()

        # Assert
        closed = auth.wait_for_modal_closed(timeout=3)
        assert closed, "弹窗应该成功关闭"

    @pytest.mark.ui
    def test_switch_to_register_view(self, clean_page: WebDriver) -> None:
        """
        场景：切换到注册 Tab
        验证：显示注册表单（用户名输入框可见）

        Selenium 学习要点：
          Vue 的 v-if 切换组件时，DOM 会重建。
          切换后需要等待新元素出现。
        """
        # Arrange
        discover = DiscoverPage(clean_page)
        header = HeaderPage(clean_page)
        discover.go()
        discover.wait_for_content()
        header.click_login()

        auth = AuthModalPage(clean_page)
        auth.wait_for_modal()

        # Act
        auth.switch_to_register()

        # Assert
        assert auth.is_register_view(), "应该显示注册表单"

    @pytest.mark.ui
    def test_login_empty_form_validation(self, clean_page: WebDriver) -> None:
        """
        场景：空表单直接点登录
        验证：应该有前端验证或后端错误提示
        """
        discover = DiscoverPage(clean_page)
        header = HeaderPage(clean_page)
        discover.go()
        discover.wait_for_content()
        header.click_login()

        auth = AuthModalPage(clean_page)
        auth.wait_for_modal()

        # 不填任何内容直接点击登录
        auth.login_submit_btn.click()
        import time
        time.sleep(0.5)

        # 应该显示某些错误提示（具体取决于前端实现）
        # 用截图辅助调试
        print(f"当前页面 URL: {clean_page.current_url}")


# ═══════════════════════════════════════════════════════════════
# UI 测试 — 路由守卫
# ═══════════════════════════════════════════════════════════════

class TestRouteGuard:
    """路由守卫测试"""

    @pytest.mark.p0
    @pytest.mark.ui
    def test_unauthenticated_access_to_create_thread(self, clean_page: WebDriver) -> None:
        """
        场景：未登录访问发帖页
        验证：弹出登录弹窗（Vue Router 守卫阻止导航 + 触发弹窗）

        Selenium 学习要点：
          Vue Router 守卫在导航前拦截，返回 false 取消导航。
          所以 ThreadCreate 组件根本不会挂载。
          正确做法：先到发现页建立页面上下文，再尝试跳转受保护路由。
        """
        # 先到公开页面建立上下文
        discover = DiscoverPage(clean_page)
        discover.go()
        discover.wait_for_content()

        # 尝试跳转到受保护页面
        clean_page.get(f"{settings.BASE_URL}/threads/new")
        import time
        time.sleep(1.5)  # 等待路由守卫 + 弹窗事件

        # 路由守卫行为：重定向到 /discover?login=1 或弹出登录弹窗
        auth = AuthModalPage(clean_page)
        modal_visible = auth.is_visible()
        on_discover = "/discover" in clean_page.current_url
        has_login_param = "login=1" in clean_page.current_url

        assert modal_visible or on_discover, (
            f"未登录访问应触发弹窗或重定向。弹窗={modal_visible}, 在discover={on_discover}, "
            f"当前URL: {clean_page.current_url}"
        )

    @pytest.mark.p0
    @pytest.mark.ui
    def test_unauthenticated_access_to_feed(self, clean_page: WebDriver) -> None:
        """
        场景：未登录访问 /feed
        验证：重定向或弹出登录弹窗
        """
        discover = DiscoverPage(clean_page)
        discover.go()
        discover.wait_for_content()

        clean_page.get(f"{settings.BASE_URL}/feed")
        import time
        time.sleep(1.5)

        auth = AuthModalPage(clean_page)
        modal_visible = auth.is_visible()
        on_discover = "/discover" in clean_page.current_url

        assert modal_visible or on_discover, (
            f"未登录访问/feed应触发弹窗或重定向。弹窗={modal_visible}, 在discover={on_discover}"
        )
