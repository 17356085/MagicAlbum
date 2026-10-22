"""
搜索与筛选测试

覆盖: 关键词搜索、标签筛选、分区筛选、用户搜索
"""

import pytest
from playwright.sync_api import Page

from tests.api.api_client import ApiClient
from tests.e2e.playwright.pages.discover_page import DiscoverPage
from tests.e2e.playwright.pages.header_page import HeaderPage


def _items(response) -> list[dict]:
    body = response.json()
    nested = body.get("data") if isinstance(body, dict) else None
    payload = nested if isinstance(nested, dict) else body
    values = payload.get("items", []) if isinstance(payload, dict) else []
    return [item for item in values if isinstance(item, dict)]


def _live_filter_values(api_client: ApiClient) -> tuple[int, str]:
    sections = api_client.list_sections(page=1, size=100)
    assert sections.status_code == 200
    section_items = _items(sections)
    assert section_items, "隔离测试栈必须返回至少一个真实分区"
    section_id = int(section_items[0]["id"])

    tags = api_client.list_popular_tags(size=20)
    assert tags.status_code == 200
    tag_items = _items(tags)
    assert tag_items, "隔离测试栈必须返回至少一个真实标签"
    tag_name = tag_items[0].get("name")
    assert isinstance(tag_name, str) and tag_name.strip()
    return section_id, tag_name.strip()


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
    def test_filter_by_tag_displays_indicator(self, page: Page, api_client: ApiClient) -> None:
        """标签筛选后显示标签提示"""
        _, tag = _live_filter_values(api_client)
        discover = DiscoverPage(page)
        discover.go_with_query(tag=tag, page=1)
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
    def test_combined_search_tag_and_section(self, page: Page, api_client: ApiClient) -> None:
        """标签+分区组合筛选"""
        section_id, tag = _live_filter_values(api_client)
        discover = DiscoverPage(page)
        discover.go_with_query(tag=tag, sectionId=section_id, page=1)
        discover.wait_for_content()

        url = page.url
        assert "tag=" in url
        assert "sectionId=" in url
