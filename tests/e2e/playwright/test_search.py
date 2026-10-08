"""
搜索与筛选测试

覆盖: 关键词搜索、标签筛选、分区筛选、用户搜索
"""

import pytest
from playwright.sync_api import Page

from tests.e2e.playwright.pages.discover_page import DiscoverPage
from tests.e2e.playwright.pages.header_page import HeaderPage


class TestSearch:
    """搜索功能测试"""

    @pytest.mark.p0
    @pytest.mark.ui
    def test_search_by_keyword(self, page: Page) -> None:
        """通过关键词搜索帖子"""
        discover = DiscoverPage(page)
        discover.go()
        discover.wait_for_content()

        header = HeaderPage(page)
        header.search("测试")

        discover.wait_for_content()
        assert "q=" in page.url

    @pytest.mark.p0
    @pytest.mark.ui
    def test_search_displays_keyword_indicator(self, page: Page) -> None:
        """搜索后页面显示关键词提示"""
        discover = DiscoverPage(page)
        discover.go_with_query(q="测试", page=1)
        discover.wait_for_content()

        assert discover.search_keyword_display.is_visible()

    @pytest.mark.p0
    @pytest.mark.ui
    def test_filter_by_tag_displays_indicator(self, page: Page) -> None:
        """标签筛选后显示标签提示"""
        discover = DiscoverPage(page)
        discover.go_with_query(tag="编程", page=1)
        discover.wait_for_content()

        assert discover.search_keyword_display.is_visible()

    @pytest.mark.p2
    @pytest.mark.ui
    def test_search_with_empty_results(self, page: Page) -> None:
        """搜索无结果"""
        discover = DiscoverPage(page)
        discover.go_with_query(q="zxcvbnm_nonexistent_999999", page=1)
        discover.wait_for_content()

        assert discover.is_empty()

    @pytest.mark.p1
    @pytest.mark.ui
    def test_combined_search_tag_and_section(self, page: Page) -> None:
        """标签+分区组合筛选"""
        discover = DiscoverPage(page)
        discover.go_with_query(tag="编程", sectionId=1, page=1)
        discover.wait_for_content()

        url = page.url
        assert "tag=" in url
        assert "sectionId=" in url
