"""
排行榜页 Page Object

路由: /ranking
功能: 按热度排序、分区筛选、Top3 特殊展示、分页
"""

from playwright.sync_api import Locator, Page

from tests.e2e.playwright.pages.base_page import BasePage
from tests.e2e.playwright.utils.wait_helpers import wait_for_loading_gone


class RankingPage(BasePage):
    """排行榜"""

    def __init__(self, page: Page, base_url: str | None = None):
        super().__init__(page, base_url)
        self.path = "/ranking"

    # ── 定位器 ────────────────────────────────────────────────

    @property
    def top_one_card(self) -> Locator:
        """Top1 大卡片"""
        return self.page.locator(".group.rounded-lg").first

    @property
    def ranking_items(self) -> Locator:
        """排行榜条目"""
        return self.page.locator(
            ".ranking-page > div.space-y-4 > div.ui-panel > div[role='link'], "
            ".ranking-page > div.space-y-4 > div.grid > div[role='link'], "
            ".ranking-page > div.space-y-4 > div.ui-panel > ul > li"
        )

    @property
    def section_filter(self) -> Locator:
        """分区筛选下拉或按钮组"""
        return self.page.locator("select, button[class*='section']").first

    @property
    def loading_indicator(self) -> Locator:
        return self.page.get_by_text("正在加载...")

    @property
    def empty_state(self) -> Locator:
        return self.page.get_by_text("暂无")

    # 分页
    @property
    def prev_page_btn(self) -> Locator:
        return self.page.get_by_role("button", name="上一页")

    @property
    def next_page_btn(self) -> Locator:
        return self.page.get_by_role("button", name="下一页")

    # ── 操作 ──────────────────────────────────────────────────

    def go(self) -> None:
        self.navigate(self.path)

    def wait_for_content(self) -> None:
        wait_for_loading_gone(self.page)

    def get_item_count(self) -> int:
        return self.ranking_items.count()

    def get_item_texts(self) -> list[str]:
        """Return the rendered text for each ranked thread, in API order."""
        return [
            (self.ranking_items.nth(index).inner_text() or "").strip()
            for index in range(self.ranking_items.count())
        ]

    def has_top_one(self) -> bool:
        return self.top_one_card.is_visible()
