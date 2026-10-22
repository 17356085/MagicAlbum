"""
Selenium Page Object 基类

Selenium 学习要点 — Page Object 模式：
  Page Object 是 Selenium 测试中最经典的设计模式，核心思想：

  1. 每个页面/组件对应一个类
  2. 定位器（Locators）集中在类的属性中
  3. 页面操作封装为方法，隐藏 Selenium API 细节
  4. 测试代码只调用 Page Object 方法，不直接操作 WebDriver

  好处：
    - 页面改版只需修改一个类
    - 测试代码可读性高（像阅读用户操作步骤）
    - 减少重复的 find_element 代码

  对比 Playwright 的 Page Object：
    - Playwright: locator 是惰性求值，自动等待
    - Selenium:  需要显式等待，WebElement 可能 stale
    - 本项目用 @property 模式让 Selenium 的 PO 更接近 Playwright 体验
"""

import time
from typing import Optional

from selenium.webdriver.remote.webdriver import WebDriver
from selenium.webdriver.remote.webelement import WebElement
from selenium.webdriver.common.by import By
from selenium.common.exceptions import NoSuchElementException

from tests.e2e.selenium.config.settings import settings
from tests.e2e.selenium.utils.wait_helpers import wait_for_loading_gone, wait_for_text_in_body


class BasePage:
    """
    所有 Page Object 的基类

    提供：
      - 页面导航 (navigate)
      - 通用等待 (wait_for_page_ready)
      - 文本检查 (is_text_visible, has_text)
      - Toast/错误消息检查
    """

    def __init__(self, driver: WebDriver):
        """
        Args:
            driver: Selenium WebDriver 实例（由 conftest.py fixture 注入）
        """
        self.driver = driver
        self.base_url = settings.BASE_URL.rstrip("/")

    # ── 导航 ──────────────────────────────────────────────────

    def navigate(self, path: str = "/") -> None:
        """
        导航到指定路径并等待页面加载完成

        用法:
            self.navigate("/discover")
            self.navigate("/threads/123")

        Selenium 学习要点：
          driver.get() 是阻塞的 — 它会等页面 load 事件触发后才返回。
          但在 Vue SPA 中，load 事件 ≠ 内容渲染完成！
          Vue 在 load 之后还要：加载 JS bundle → 初始化 Vue → 调用 API → 渲染 DOM
          所以 navigate 后必须额外等待。

        Playwright 对比：
          page.goto(url, wait_until="networkidle") 一步到位
          Selenium 需要手动加 wait_for_page_ready()
        """
        url = f"{self.base_url}{path}"
        self.driver.get(url)
        self.wait_for_page_ready()

    def wait_for_page_ready(self) -> None:
        """
        等待 Vue 页面异步渲染完成

        会等待：
          1. 加载中... 文案消失
          2. 额外缓冲 0.3s（让 v-if 切换完成）
        """
        wait_for_loading_gone(self.driver)
        time.sleep(0.3)  # 缓冲 v-if/v-else DOM 切换

    # ── 通用检查 ──────────────────────────────────────────────

    def is_text_visible(self, text: str) -> bool:
        """
        检查页面上是否随处可见指定文本

        注意：is_displayed() 对 body 级别元素不适用
        """
        return text in self.driver.find_element(By.TAG_NAME, "body").text

    def has_text(self, text: str) -> bool:
        """检查页面源码中是否包含文本（包括不可见元素）"""
        return text in self.driver.page_source

    def get_page_title(self) -> str:
        """获取页面标题"""
        return self.driver.title

    def get_current_url(self) -> str:
        """获取当前 URL"""
        return self.driver.current_url

    # ── 消息检查 ──────────────────────────────────────────────

    def get_error_text(self) -> Optional[str]:
        """
        查找页面上可见的错误消息

        Tailwind 的红色系 class:
          .text-red-600, .text-red-500, .text-red-400
        """
        error_selectors = [
            "[role='alert']",
            ".ui-error",
            ".text-red-600", ".text-red-500", ".text-red-400",
            "[class*='text-red']",
        ]
        for sel in error_selectors:
            for el in self.driver.find_elements(By.CSS_SELECTOR, sel):
                if el.is_displayed():
                    return el.text
        return None

    def get_success_text(self) -> Optional[str]:
        """
        查找页面上可见的成功消息

        Tailwind 的绿色系 class:
          .text-green-600, .text-green-500
        """
        success_selectors = [
            ".text-green-600", ".text-green-500", ".text-green-400",
            "[class*='text-emerald']",
            "[class*='text-green']",
        ]
        for sel in success_selectors:
            for el in self.driver.find_elements(By.CSS_SELECTOR, sel):
                if el.is_displayed():
                    return el.text
        return None

    def wait_for_toast(self, text: str, timeout: float = 5) -> bool:
        """
        等待 Toast 消息出现

        Toast 通常用 CSS transition 动画出现
        需要等待文本变成可见
        """
        return wait_for_text_in_body(self.driver, text, timeout)

    # ── 脚本执行 ──────────────────────────────────────────────

    def execute_script(self, script: str, *args):
        """
        执行 JavaScript 代码

        Selenium 学习要点：
          driver.execute_script() 是最强大的 escape hatch。
          当 Selenium API 无法完成操作时，可以直接执行 JS。

          常见用途：
            - 滚动到元素: arguments[0].scrollIntoView()
            - 操作 localStorage: localStorage.setItem(...)
            - 触发 Vue 事件: window.dispatchEvent(new CustomEvent(...))
        """
        return self.driver.execute_script(script, *args)

    def scroll_to_element(self, element: WebElement) -> None:
        """
        滚动页面使元素可见

        重要：Selenium 对不可见元素执行 click() 会抛异常
        """
        self.execute_script("arguments[0].scrollIntoView({block: 'center'});", element)
        time.sleep(0.3)  # 等待滚动动画

    def clear_local_storage(self) -> None:
        """清空 localStorage — 在测试用例之间调用避免数据污染"""
        self.execute_script("localStorage.clear();")

    # ── 元素查找快捷方法 ─────────────────────────────────────

    def find(self, css: str) -> WebElement:
        """用 CSS 选择器查找单个元素"""
        return self.driver.find_element(By.CSS_SELECTOR, css)

    def find_all(self, css: str) -> list[WebElement]:
        """用 CSS 选择器查找所有匹配元素"""
        return self.driver.find_elements(By.CSS_SELECTOR, css)

    def find_by_text(self, tag: str, text: str) -> WebElement:
        """通过标签和文本查找元素"""
        xpath = f"//{tag}[contains(text(), '{text}')]"
        return self.driver.find_element(By.XPATH, xpath)

    def find_by_placeholder(self, placeholder: str) -> WebElement:
        """通过 placeholder 属性查找输入框"""
        return self.driver.find_element(
            By.CSS_SELECTOR, f"input[placeholder*='{placeholder}']"
        )
