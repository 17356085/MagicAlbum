"""Authentication API tests."""

import pytest

from tests.api.api_client import ApiClient
from tests.conftest import TEST_EMAIL, TEST_PASSWORD
from tests.utils.data_generator import (
    unique_email,
    unique_phone,
    unique_username,
)


class TestAuthAPI:
    """Login, authorization, and availability endpoints."""

    @pytest.mark.api
    def test_login_with_valid_credentials(self, api_client: ApiClient) -> None:
        response = api_client.login_email_password(TEST_EMAIL, TEST_PASSWORD)
        assert response.status_code == 200, f"期望200，实际{response.status_code}"

        data = response.json()
        payload = data.get("data", data)
        token = payload.get("accessToken") or payload.get("access_token") or payload.get("token")
        assert token, "响应中应包含 token"

    @pytest.mark.api
    def test_login_with_invalid_password(self, api_client: ApiClient) -> None:
        response = api_client.login_email_password(
            TEST_EMAIL, f"invalid-{unique_username()}"
        )
        assert response.status_code in (401, 400, 403), f"期望 4xx，实际 {response.status_code}"

    @pytest.mark.api
    def test_access_protected_endpoint_without_token(self, api_client: ApiClient) -> None:
        response = api_client.get_current_user()
        assert response.status_code in (401, 403), f"期望 401/403，实际 {response.status_code}"

    @pytest.mark.api
    def test_check_username_available(self, api_client: ApiClient) -> None:
        response = api_client.check_username_available(unique_username())
        assert response.status_code == 200, f"期望200，实际 {response.status_code}"


@pytest.mark.slow
class TestRegistration:
    """Registration API tests."""

    @pytest.mark.api
    def test_register_new_user(self, api_client: ApiClient) -> None:
        response = api_client.register(
            username=unique_username(),
            email=unique_email(),
            phone=unique_phone(),
            password=TEST_PASSWORD,
        )
        assert response.status_code == 201, f"期望 201，实际 {response.status_code}"

    @pytest.mark.api
    def test_register_duplicate_username(self, api_client: ApiClient) -> None:
        username = unique_username()
        first_response = api_client.register(
            username=username,
            email=unique_email(),
            phone=unique_phone(),
            password=TEST_PASSWORD,
        )
        assert first_response.status_code == 201, f"首次注册应返回 201，实际 {first_response.status_code}"

        duplicate_response = api_client.register(
            username=username,
            email=unique_email(),
            phone=unique_phone(),
            password=TEST_PASSWORD,
        )
        assert duplicate_response.status_code in (400, 409), (
            f"重复用户名应返回 400/409，实际 {duplicate_response.status_code}"
        )
