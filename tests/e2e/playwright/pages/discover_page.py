"""
发现页 (Discover) Page Object

路由: /discover
功能: 帖子列表浏览、分区/标签/关键词筛选、分页
"""

from playwright.sync_api import Locator, Page

from tests.e2e.playwright.pages.base_page import BasePage
from tests.e2e.playwright.utils.wait_helpers import wait_for_loading_gone


class DiscoverPage(BasePage):
    """发现页"""

    def __init__(self, page: Page, base_url: str | None = None):
        super().__init__(page, base_url)
        self.path = "/discover"

    # ── 定位器 ────────────────────────────────────────────────

    @property
    def thread_list(self) -> Locator:
        """帖子列表容器"""
        return self.page.locator(".rounded-xl.border.border-gray-100 ul")

    @property
    def thread_cards(self) -> Locator:
        """所有帖子卡片"""
        return self.page.locator("ul.space-y-4 > li.group")

    def thread_card_by_index(self, index: int) -> Locator:
        """按索引获取帖子卡片 (0-based)"""
        return self.thread_cards.nth(index)

    @property
    def first_thread_link(self) -> Locator:
        """第一个帖子的链接"""
        return self.thread_cards.first.locator("a[href*='/threads/']").first

    @property
    def loading_indicator(self) -> Locator:
        """加载中指示器"""
        return self.page.get_by_text("正在加载...")

    @property
    def empty_state(self) -> Locator:
        """空状态提示"""
        return self.page.get_by_text("暂无帖子")

    @property
    def error_state(self) -> Locator:
        """错误状态"""
        return self.page.locator(".text-red-600")

    # 搜索筛选
    @property
    def search_keyword_display(self) -> Locator:
        """搜索关键词显示"""
        return self.page.locator(".mb-2.text-xs")

    # 分页
    @property
    def prev_page_btn(self) -> Locator:
        return self.page.get_by_role("button", name="上一页")

    @property
    def next_page_btn(self) -> Locator:
        return self.page.get_by_role("button", name="下一页")

    @property
    def page_input(self) -> Locator:
        return self.page.locator("input[inputmode='numeric']")

    @property
    def page_info(self) -> Locator:
        return self.page.locator("text=/共 \\d+ 条/")

    @property
    def total_pages_span(self) -> Locator:
        return self.page.locator("text=/\\d+ 页/").last

    # ── 操作 ──────────────────────────────────────────────────

    def go(self) -> None:
        """导航到发现页"""
        self.navigate(self.path)

    def go_with_query(self, **params) -> None:
        """带查询参数导航"""
        from urllib.parse import urlencode

        query_str = urlencode(params)
        self.navigate(f"{self.path}?{query_str}")

    def wait_for_content(self) -> None:
        """等待帖子列表或空状态出现"""
        wait_for_loading_gone(self.page)
        self.page.wait_for_timeout(300)  # 等待 v-else 切换

    def get_thread_count(self) -> int:
        """获取当前显示的帖子数量"""
        return self.thread_cards.count()

    def click_thread(self, index: int = 0) -> None:
        """点击指定索引的帖子"""
        card = self.thread_card_by_index(index)
        card.locator("a[href*='/threads/']").first.click()
        self.page.wait_for_url("**/threads/*", timeout=10000)

    def click_tag(self, tag_name: str) -> None:
        """点击标签"""
        self.page.get_by_text(f"#{tag_name}").first.click()
        self.wait_for_content()

    def go_to_page(self, page_num: int) -> None:
        """跳转到指定页"""
        self.page_input.fill(str(page_num))
        self.page_input.press("Enter")
        self.wait_for_content()

    def go_prev_page(self) -> None:
        """上一页"""
        self.prev_page_btn.click()
        self.wait_for_content()

    def go_next_page(self) -> None:
        """下一页"""
        self.next_page_btn.click()
        self.wait_for_content()

    def get_current_page(self) -> int:
        """获取当前页码"""
        raw = self.page_input.input_value()
        return int(raw) if raw.isdigit() else 1

    def get_total_count(self) -> int:
        """获取总条数"""
        text = self.page_info.text_content() or "0"
        import re

        m = re.search(r"共 (\d+) 条", text)
        return int(m.group(1)) if m else 0

    # ── 状态检查 ──────────────────────────────────────────────

    def is_loading(self) -> bool:
        return self.loading_indicator.is_visible()

    def is_empty(self) -> bool:
        return self.empty_state.is_visible()

    def has_threads(self) -> bool:
        return self.get_thread_count() > 0

    def has_pagination(self) -> bool:
        return self.page_info.is_visible()
