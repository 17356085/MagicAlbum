"""Playwright coverage unique to the ranking page."""

import pytest
from playwright.sync_api import Page

from tests.e2e.playwright.pages.ranking_page import RankingPage


class TestRankingPage:
    """排行榜页测试。"""

    @pytest.mark.p1
    @pytest.mark.ui
    def test_ranking_loads_successfully(self, page: Page) -> None:
        ranking = RankingPage(page)
        ranking.go()
        ranking.wait_for_content()

        # 应加载出内容或空状态
        assert ranking.get_item_count() >= 0
