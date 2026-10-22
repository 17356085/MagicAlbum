"""
发现页 Page Object

Selenium 学习要点 — 列表页面处理：
  1. v-for 渲染的列表：元素数量是动态的
     必须先等待第一个元素出现（列表加载完成）

  2. 筛选/搜索后的列表刷新：
     Vue 会重新渲染 DOM，之前获取的 WebElement 会 Stale
     每次操作后重新获取元素

  3. 分页组件：
     输入页码 → 按 Enter → 等待 URL 变化 → 等待列表刷新
"""

from selenium.webdriver.remote.webdriver import WebDriver
from selenium.webdriver.remote.webelement import WebElement
from selenium.webdriver.common.by import By
from selenium.webdriver.common.keys import Keys
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.common.exceptions import TimeoutException, NoSuchElementException

from tests.e2e.selenium.pages.base_page import BasePage
from tests.e2e.selenium.config.settings import settings


class DiscoverPage(BasePage):
    """发现页 — 帖子浏览、筛选、搜索、分页"""

    def __init__(self, driver: WebDriver):
        super().__init__(driver)
        self.path = "/discover"

    # ═══════════════════════════════════════════════════════════
    # 定位器
    # ═══════════════════════════════════════════════════════════

    # 帖子列表容器
    THREAD_LIST_CSS = ".discover-page > .ui-panel > ul"
    # 帖子卡片（当前 li 使用 ui-list-row，group 在内部 router-link 上）
    THREAD_CARDS_CSS = ".discover-page > .ui-panel > ul > li.ui-list-row"
    ERROR_CSS = ".discover-page [role='alert']"

    # 状态指示器
    LOADING_TEXT = "正在加载..."
    EMPTY_TEXT = "暂无帖子"

    # 分页
    PREV_BTN_XPATH = "//nav[@aria-label='帖子分页']//button[contains(normalize-space(.),'上一页')]"
    NEXT_BTN_XPATH = "//nav[@aria-label='帖子分页']//button[contains(normalize-space(.),'下一页')]"
    PAGE_INPUT_CSS = "nav[aria-label='帖子分页'] input[aria-label='页码'][inputmode='numeric']"

    # ── 定位器属性 ─────────────────────────────────────────

    @property
    def thread_cards(self) -> list[WebElement]:
        """所有帖子卡片"""
        return self.driver.find_elements(By.CSS_SELECTOR, self.THREAD_CARDS_CSS)

    @property
    def first_thread_link(self) -> WebElement:
        """第一个帖子的链接"""
        card = self.thread_cards[0]
        return card.find_element(By.CSS_SELECTOR, "a[href*='/threads/']")

    @property
    def prev_page_btn(self) -> WebElement:
        return self.driver.find_element(By.XPATH, self.PREV_BTN_XPATH)

    @property
    def next_page_btn(self) -> WebElement:
        return self.driver.find_element(By.XPATH, self.NEXT_BTN_XPATH)

    @property
    def page_input(self) -> WebElement:
        return self.driver.find_element(By.CSS_SELECTOR, self.PAGE_INPUT_CSS)

    @property
    def empty_state(self) -> WebElement:
        return self.driver.find_element(By.CSS_SELECTOR, ".discover-page > .ui-empty")

    @property
    def loading_indicator(self) -> WebElement:
        return self.driver.find_element(By.CSS_SELECTOR, ".discover-page [role='status']")

    # ═══════════════════════════════════════════════════════════
    # 操作
    # ═══════════════════════════════════════════════════════════

    def go(self) -> None:
        """导航到发现页"""
        self.navigate(self.path)

    def go_with_query(self, **params) -> None:
        """
        带查询参数导航

        用法:
            discover.go_with_query(sectionId=section_id, page=2)
            discover.go_with_query(tag=tag)
        """
        from urllib.parse import urlencode
        query_str = urlencode(params)
        self.navigate(f"{self.path}?{query_str}")

    def wait_for_content(self) -> None:
        """
        等待帖子列表或空状态出现

        Selenium 学习要点 — 正向等待：
          不能只等待 loading 消失——如果 Vue 还没挂载，loading 文本根本不存在，
          wait_for_loading_gone 会立即返回，后续操作可能拿到空白页面。

          正确做法：正向等待"期望内容出现"。
        """
        wait = WebDriverWait(self.driver, 15)
        wait.until(
            lambda d: (
                not d.find_elements(By.CSS_SELECTOR, ".discover-page [role='status']")
                and (
                    bool(d.find_elements(By.CSS_SELECTOR, self.THREAD_CARDS_CSS))
                    or bool(d.find_elements(By.CSS_SELECTOR, ".discover-page > .ui-empty"))
                    or bool(d.find_elements(By.CSS_SELECTOR, self.ERROR_CSS))
                )
            )
        )

    def get_thread_count(self) -> int:
        """获取当前显示的帖子数量"""
        return len(self.thread_cards)

    def click_thread(self, index: int = 0) -> None:
        """
        点击第 N 个帖子，跳转到详情

        Selenium 学习要点：
          点击链接会导致页面跳转，需要等待新页面加载完成。
          在 Vue SPA 中，router-link 不会触发整页刷新，
          但 URL 和 DOM 都会变化。
        """
        cards = self.thread_cards
        if index >= len(cards):
            raise IndexError(f"只有 {len(cards)} 个帖子，无法访问索引 {index}")

        link = cards[index].find_element(By.CSS_SELECTOR, "a[href*='/threads/']")
        self.scroll_to_element(link)
        link.click()

        # 等待 URL 变化表明导航完成
        WebDriverWait(self.driver, 10).until(
            lambda d: "/threads/" in d.current_url and not d.current_url.endswith("#")
        )
        self.wait_for_page_ready()

    def go_to_page(self, page_num: int) -> None:
        """
        跳转到指定页码

        Selenium 学习要点 — 输入框交互：
          1. clear() 清空
          2. send_keys() 输入
          3. send_keys(Keys.ENTER) 提交
          （也可以用 submit() 或 click 搜索按钮）
        """
        self.page_input.clear()
        self.page_input.send_keys(str(page_num))
        self.page_input.send_keys(Keys.ENTER)
        WebDriverWait(self.driver, 10).until(lambda d: self.get_current_page() == page_num)
        self.wait_for_content()

    def go_next_page(self) -> None:
        """翻到下一页"""
        previous_page = self.get_current_page()
        self.next_page_btn.click()
        WebDriverWait(self.driver, 10).until(lambda d: self.get_current_page() != previous_page)
        self.wait_for_content()

    def go_prev_page(self) -> None:
        """翻到上一页"""
        previous_page = self.get_current_page()
        self.prev_page_btn.click()
        WebDriverWait(self.driver, 10).until(lambda d: self.get_current_page() != previous_page)
        self.wait_for_content()

    def get_current_page(self) -> int:
        """获取当前页码"""
        inputs = self.driver.find_elements(By.CSS_SELECTOR, self.PAGE_INPUT_CSS)
        if not inputs:
            return 1
        value = inputs[0].get_attribute("value")
        return int(value) if value and value.isdigit() else 1

    # ═══════════════════════════════════════════════════════════
    # 状态检查
    # ═══════════════════════════════════════════════════════════

    def is_loading(self) -> bool:
        try:
            return self.loading_indicator.is_displayed()
        except NoSuchElementException:
            return False

    def is_empty(self) -> bool:
        try:
            return self.empty_state.is_displayed()
        except NoSuchElementException:
            return False

    def has_threads(self) -> bool:
        return self.get_thread_count() > 0

    def has_pagination(self) -> bool:
        """是否有分页（多页时显示）"""
        try:
            # 分页控件在单页结果也会渲染；只有下一页可用时才代表确有多页。
            return self.next_page_btn.is_enabled()
        except NoSuchElementException:
            return False
