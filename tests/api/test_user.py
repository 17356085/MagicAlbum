"""User follow and profile API tests."""

import pytest

from tests.api.api_client import ApiClient


class TestFollowAPI:
    """关注、取关和关注关系列表接口测试。"""

    @pytest.fixture
    def another_user_id(self, auth_api_client: ApiClient) -> int | None:
        response = auth_api_client.list_users(page=1, size=5)
        if response.status_code != 200:
            return None
        data = response.json()
        items = data.get("items", []) or data.get("data", {}).get("items", [])
        return items[0]["id"] if items else None

    @pytest.mark.api
    @pytest.mark.p1
    def test_follow_and_unfollow_user(
        self, auth_api_client: ApiClient, another_user_id: int | None
    ) -> None:
        if not another_user_id:
            pytest.skip("无其他用户可用于测试")
        follow_response = auth_api_client.follow_user(another_user_id)
        assert follow_response.status_code == 200
        unfollow_response = auth_api_client.unfollow_user(another_user_id)
        assert unfollow_response.status_code == 200

    @pytest.mark.api
    def test_get_user_following(
        self, auth_api_client: ApiClient, another_user_id: int | None
    ) -> None:
        if not another_user_id:
            pytest.skip("无其他用户ID")
        response = auth_api_client.get_user_following(another_user_id)
        assert response.status_code == 200

    @pytest.mark.api
    def test_get_user_followers(
        self, auth_api_client: ApiClient, another_user_id: int | None
    ) -> None:
        if not another_user_id:
            pytest.skip("无其他用户ID")
        response = auth_api_client.get_user_followers(another_user_id)
        assert response.status_code == 200
