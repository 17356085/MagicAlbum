"""
Base Page Object — 所有 Page Object 的基类。

封装通用的页面操作：
- 导航
- 等待异步渲染完成
- 错误/成功消息检查
"""

import os

from playwright.sync_api import Page, TimeoutError as PlaywrightTimeoutError

from tests.e2e.playwright.utils.wait_helpers import wait_for_loading_gone, wait_for_text_present


class BasePage:
    """Page Object 基类"""

    def __init__(self, page: Page, base_url: str | None = None):
        self.page = page
        self.base_url = (base_url or os.getenv("TEST_BASE_URL", "http://localhost:5173")).rstrip("/")

    # ── 导航 ─────────────────────────────────────────────────

    def navigate(self, path: str = "/") -> None:
        """导航到指定路径并等待加载完成"""
        url = f"{self.base_url}{path}"
        self.page.goto(url, wait_until="domcontentloaded")
        self.wait_for_page_ready()

    def wait_for_page_ready(self) -> None:
        """等待 Vue 页面渲染完成"""
        wait_for_loading_gone(self.page)

    # ── 通用检查 ─────────────────────────────────────────────

    def is_text_visible(self, text: str) -> bool:
        """检查页面上是否包含指定文本"""
        return self.page.locator(f"text={text}").is_visible()

    def has_text(self, text: str) -> bool:
        """检查页面 body 中是否包含指定文本（包括不可见元素）"""
        return text in self.page.content()

    def get_error_text(self) -> str | None:
        """获取页面上可见的错误文本"""
        error_selectors = [
            ".text-red-600",
            ".text-red-500",
            ".text-red-400",
            "[class*='text-red']",
        ]
        for sel in error_selectors:
            el = self.page.locator(sel).first
            if el.is_visible():
                return el.text_content()
        return None

    def get_success_text(self) -> str | None:
        """获取页面上可见的成功文本"""
        success_selectors = [
            ".text-green-600",
            ".text-green-500",
            ".text-green-400",
            "[class*='text-green']",
        ]
        for sel in success_selectors:
            el = self.page.locator(sel).first
            if el.is_visible():
                return el.text_content()
        return None

    def wait_for_toast(self, text: str, timeout: float = 5000) -> bool:
        """等待 Toast 消息出现"""
        try:
            wait_for_text_present(self.page, text, timeout)
            return True
        except PlaywrightTimeoutError:
            return False

    # ── 清理 ─────────────────────────────────────────────────

    def clear_local_storage(self) -> None:
        """清理 localStorage（测试用例之间调用）"""
        self.page.evaluate("localStorage.clear()")
