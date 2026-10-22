"""
用户主页 Page Object

Selenium 学习要点 — 状态切换按钮：
  关注/取消关注按钮是典型的状态切换 UI：
    - 未关注时显示"关注"
    - 已关注时显示"已关注"或"取消关注"
    - 点击后 API 返回 → 按钮文本切换
    测试时需要等待按钮状态更新完成
"""

import time

from selenium.webdriver.remote.webdriver import WebDriver
from selenium.webdriver.remote.webelement import WebElement
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.common.exceptions import TimeoutException, NoSuchElementException

from tests.e2e.selenium.pages.base_page import BasePage
from tests.e2e.selenium.config.settings import settings


class UserProfilePage(BasePage):
    """用户主页"""

    def __init__(self, driver: WebDriver):
        super().__init__(driver)

    # ═══════════════════════════════════════════════════════════
    # 定位器
    # ═══════════════════════════════════════════════════════════

    USERNAME_XPATH = "//section[contains(@class,'ui-page')]//header//h1"
    BIO_CSS = "section.ui-page .prose"
    FOLLOW_BTN_XPATH = "//section[contains(@class,'ui-page')]//header//button[normalize-space(.)='关注' or normalize-space(.)='已关注' or normalize-space(.)='处理中...']"

    # 用户帖子列表（复用 DiscoverPage 的样式）
    USER_THREADS_CSS = "section.ui-page > .ui-panel ul > li.ui-list-row"

    # ── 定位器属性 ─────────────────────────────────────────

    @property
    def username_heading(self) -> WebElement:
        return self.driver.find_element(By.XPATH, self.USERNAME_XPATH)

    @property
    def follow_button(self) -> WebElement:
        return self.driver.find_element(By.XPATH, self.FOLLOW_BTN_XPATH)

    @property
    def user_threads(self) -> list[WebElement]:
        return self.driver.find_elements(By.CSS_SELECTOR, self.USER_THREADS_CSS)

    # ═══════════════════════════════════════════════════════════
    # 操作
    # ═══════════════════════════════════════════════════════════

    def go(self, user_id: int) -> None:
        """导航到用户主页"""
        self.navigate(f"/users/{user_id}")

    def wait_for_content(self) -> None:
        """等待用户信息加载"""
        WebDriverWait(self.driver, 10).until(
            EC.visibility_of_element_located((By.XPATH, self.USERNAME_XPATH))
        )

    def click_follow(self) -> None:
        """
        点击关注/取消关注按钮

        Selenium 学习要点 — 按钮文本断言：
          点击后按钮文本会变化，但需要等待 Vue 响应式更新。
          WebDriverWait 用 lambda 等待按钮文本改变。
        """
        old_text = self.follow_button.text
        self.follow_button.click()
        # 等待按钮文本变化（说明 API 响应已处理）
        WebDriverWait(self.driver, 5).until(
            lambda d: self.follow_button.text != old_text
        )

    def get_thread_count(self) -> int:
        """获取用户帖子数量"""
        return len(self.user_threads)

    # ═══════════════════════════════════════════════════════════
    # 状态检查
    # ═══════════════════════════════════════════════════════════

    def is_following(self) -> bool:
        """当前是否已关注"""
        try:
            return "取消关注" in self.follow_button.text or "已关注" in self.follow_button.text
        except NoSuchElementException:
            return False

    def get_username(self) -> str:
        """获取用户名"""
        return self.username_heading.text.strip()

    def has_threads(self) -> bool:
        return self.get_thread_count() > 0
