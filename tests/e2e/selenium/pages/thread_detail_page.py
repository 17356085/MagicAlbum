"""
帖子详情页 Page Object

Selenium 学习要点 — 内容页面处理：
  1. 页面可能返回 404 状态（帖子不存在）
     需要同时处理正常和异常两种状态

  2. 评论区是嵌套组件
     可以作为独立的 Page Component 封装
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


class ThreadDetailPage(BasePage):
    """帖子详情页"""

    def __init__(self, driver: WebDriver):
        super().__init__(driver)

    # ═══════════════════════════════════════════════════════════
    # 定位器
    # ═══════════════════════════════════════════════════════════

    TITLE_XPATH = "//article//h1"
    CONTENT_CSS = ".prose, .markdown-body"
    AUTHOR_LINK_CSS = "a[href*='/users/']"
    NOT_FOUND_TEXT = "未找到帖子"
    LIKE_BTN_XPATH = "//section[@aria-label='帖子互动']//button[.//span[normalize-space(.)='点赞' or normalize-space(.)='已点赞']]"

    # 评论区
    COMMENT_INPUT_CSS = "textarea[placeholder*='支持基础 Markdown']"
    COMMENT_SUBMIT_XPATH = "//button[contains(text(),'评论') or contains(text(),'发布')]"
    COMMENT_CARDS_CSS = "ul > li.ui-list-row"

    # ── 定位器属性 ─────────────────────────────────────────

    @property
    def title(self) -> WebElement:
        return self.driver.find_element(By.XPATH, self.TITLE_XPATH)

    @property
    def content(self) -> WebElement:
        return self.driver.find_element(By.CSS_SELECTOR, self.CONTENT_CSS)

    @property
    def author_link(self) -> WebElement:
        return self.driver.find_element(By.CSS_SELECTOR, self.AUTHOR_LINK_CSS)

    @property
    def like_button(self) -> WebElement:
        return self.driver.find_element(By.XPATH, self.LIKE_BTN_XPATH)

    @property
    def comment_input(self) -> WebElement:
        return self.driver.find_element(By.CSS_SELECTOR, self.COMMENT_INPUT_CSS)

    @property
    def comment_submit_btn(self) -> WebElement:
        return self.driver.find_element(By.XPATH, self.COMMENT_SUBMIT_XPATH)

    @property
    def comment_cards(self) -> list[WebElement]:
        return self.driver.find_elements(By.CSS_SELECTOR, self.COMMENT_CARDS_CSS)

    # ═══════════════════════════════════════════════════════════
    # 操作
    # ═══════════════════════════════════════════════════════════

    def go(self, thread_id: int) -> None:
        """导航到帖子详情页"""
        self.navigate(f"/threads/{thread_id}")

    def wait_for_content(self) -> None:
        """
        等待页面内容加载完成

        可能出现的情况：
          1. 帖子存在 → 标题和内容渲染
          2. 帖子不存在 → "未找到"提示
        """
        WebDriverWait(self.driver, 10).until(
            lambda d: (
                len(d.find_elements(By.XPATH, self.TITLE_XPATH)) > 0
                or self.NOT_FOUND_TEXT in d.find_element(By.TAG_NAME, "body").text
            )
        )
        time.sleep(0.3)

    def click_like(self) -> None:
        """点赞帖子"""
        self.like_button.click()
        time.sleep(0.5)  # 等待 API 响应更新 UI

    def fill_comment(self, text: str) -> None:
        """填写评论"""
        self.comment_input.clear()
        self.comment_input.send_keys(text)

    def submit_comment(self) -> None:
        """提交评论"""
        WebDriverWait(self.driver, 5).until(
            EC.element_to_be_clickable((By.XPATH, self.COMMENT_SUBMIT_XPATH))
        ).click()
        time.sleep(1)  # 等待乐观更新

    def post_comment(self, text: str) -> None:
        """填写并提交评论的快捷方法"""
        self.fill_comment(text)
        self.submit_comment()

    def get_comment_count(self) -> int:
        """获取评论数量"""
        return len(self.comment_cards)

    # ═══════════════════════════════════════════════════════════
    # 状态检查
    # ═══════════════════════════════════════════════════════════

    def is_loaded(self) -> bool:
        """帖子是否正常加载"""
        try:
            return self.title.is_displayed()
        except NoSuchElementException:
            return False

    def is_not_found(self) -> bool:
        """帖子是否不存在（或加载出错）"""
        body_text = self.driver.find_element(By.TAG_NAME, "body").text
        return (
            self.NOT_FOUND_TEXT in body_text
            or "正在加载" not in body_text  # 至少不是一直loading
        )

    def has_comments(self) -> bool:
        """是否有评论"""
        return self.get_comment_count() > 0
