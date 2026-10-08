"""Thread API tests."""

import pytest

from tests.api.api_client import ApiClient


class TestThreadLikeAPI:
    """帖子点赞和取消点赞接口测试。"""

    @pytest.mark.api
    def test_like_and_unlike_thread(self, auth_api_client: ApiClient) -> None:
        response = auth_api_client.list_threads(page=1, size=1)
        assert response.status_code == 200

        data = response.json()
        items = data.get("items", []) or data.get("data", {}).get("items", [])
        if not items:
            pytest.skip("无可用帖子进行点赞测试")

        thread_id = items[0].get("id")
        assert thread_id, "帖子应有 id"

        like_response = auth_api_client.like_thread(thread_id)
        assert like_response.status_code == 200, (
            f"点赞失败: {like_response.status_code}"
        )
        unlike_response = auth_api_client.unlike_thread(thread_id)
        assert unlike_response.status_code == 200, (
            f"取消点赞失败: {unlike_response.status_code}"
        )
