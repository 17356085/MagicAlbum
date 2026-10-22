"""Playwright coverage unique to the ranking page."""

import pytest
from playwright.sync_api import Page, expect

from tests.e2e.playwright.pages.ranking_page import RankingPage


class TestRankingPage:
    """排行榜页测试。"""

    @pytest.mark.p1
    @pytest.mark.ui
    def test_ranking_loads_successfully(self, page: Page) -> None:
        ranking = RankingPage(page)
        with page.expect_response(
            lambda response: (
                response.request.method == "GET"
                and response.url.split("?", 1)[0].rstrip("/").endswith(
                    "/threads/ranking"
                )
            ),
            timeout=15000,
        ) as ranking_response_info:
            ranking.go()

        ranking_response = ranking_response_info.value
        assert ranking_response.status == 200
        assert ranking_response.ok

        body = ranking_response.json()
        payload = body.get("data") if isinstance(body, dict) else None
        if not isinstance(payload, dict):
            payload = body
        assert isinstance(payload, dict)

        api_items = payload.get("items")
        assert isinstance(api_items, list) and api_items, (
            "排行榜 API 应返回已 seed 的帖子"
        )
        api_titles = [
            str(item.get("title") or "").strip()
            for item in api_items
            if isinstance(item, dict)
        ]
        assert len(api_titles) == len(api_items)
        assert all(api_titles), "排行榜 API 返回的帖子必须包含标题"

        ranking.wait_for_content()
        expect(ranking.ranking_items).to_have_count(len(api_titles))
        rendered_items = ranking.get_item_texts()
        assert all(title in rendered for title, rendered in zip(api_titles, rendered_items))
