"""AI summary API tests."""

import pytest

from tests.api.api_client import ApiClient


class TestAISummary:
    """AI 摘要接口测试。"""

    @pytest.mark.p2
    @pytest.mark.api
    def test_get_summary_for_nonexistent_thread(
        self, auth_api_client: ApiClient
    ) -> None:
        response = auth_api_client.get_summary(99999999)
        assert response.status_code in (200, 404)

    @pytest.mark.p2
    @pytest.mark.api
    def test_trigger_summary(self, auth_api_client: ApiClient) -> None:
        response = auth_api_client.list_threads(page=1, size=1)
        if response.status_code != 200:
            pytest.skip("无法获取帖子列表")

        data = response.json()
        items = data.get("items", []) or data.get("data", {}).get("items", [])
        if not items:
            pytest.skip("无可用帖子")

        response = auth_api_client.trigger_summary(items[0]["id"], force=True)
        if response.status_code == 503:
            pytest.skip("AI服务不可用")
        assert response.status_code in (200, 202)
