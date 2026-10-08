"""Comment API tests."""

import pytest

from tests.api.api_client import ApiClient
from tests.utils.data_generator import unique_comment


class TestPostsAPI:
    """评论创建、列表、删除和点赞接口测试。"""

    @pytest.fixture
    def existing_thread(self, auth_api_client: ApiClient) -> int | None:
        response = auth_api_client.list_threads(page=1, size=1)
        if response.status_code != 200:
            return None
        data = response.json()
        items = data.get("items", []) or data.get("data", {}).get("items", [])
        return items[0]["id"] if items else None

    @pytest.mark.api
    def test_create_post(
        self, auth_api_client: ApiClient, existing_thread: int | None
    ) -> None:
        if not existing_thread:
            pytest.skip("无可用帖子")
        response = auth_api_client.create_post(existing_thread, unique_comment())
        assert response.status_code in (200, 201)

    @pytest.mark.api
    def test_list_posts(
        self, auth_api_client: ApiClient, existing_thread: int | None
    ) -> None:
        if not existing_thread:
            pytest.skip("无可用帖子")
        response = auth_api_client.list_posts(existing_thread, page=1, size=10)
        assert response.status_code == 200

    @pytest.mark.api
    def test_delete_own_post(
        self, auth_api_client: ApiClient, existing_thread: int | None
    ) -> None:
        if not existing_thread:
            pytest.skip("无可用帖子")

        create_response = auth_api_client.create_post(
            existing_thread, unique_comment()
        )
        if create_response.status_code not in (200, 201):
            pytest.skip("无法创建评论")

        data = create_response.json()
        post_id = data.get("id") or data.get("data", {}).get("id")
        if not post_id:
            pytest.skip("无法获取评论ID")

        delete_response = auth_api_client.delete_post(post_id)
        assert delete_response.status_code in (200, 204)

    @pytest.mark.api
    def test_like_and_unlike_post(
        self, auth_api_client: ApiClient, existing_thread: int | None
    ) -> None:
        if not existing_thread:
            pytest.skip("无可用帖子")

        list_response = auth_api_client.list_posts(existing_thread, page=1, size=1)
        if list_response.status_code != 200:
            pytest.skip("无法获取评论列表")

        data = list_response.json()
        items = data.get("items", []) or data.get("data", {}).get("items", [])
        if not items:
            pytest.skip("无可用评论")

        post_id = items[0]["id"]
        like_response = auth_api_client.like_post(post_id)
        assert like_response.status_code == 200
        unlike_response = auth_api_client.unlike_post(post_id)
        assert unlike_response.status_code == 200
