"""Account-persistent bookmark API contract against a running local backend."""

from uuid import uuid4

import pytest

from tests.api.api_client import ApiClient


@pytest.mark.api
def test_bookmark_round_trip_and_effective_page_size(auth_api_client: ApiClient) -> None:
    sections_response = auth_api_client.list_sections(page=1, size=1)
    assert sections_response.status_code == 200
    sections = sections_response.json().get("items", [])
    assert sections, "本地数据库应已初始化至少一个分区"

    created = auth_api_client.create_thread(
        int(sections[0]["id"]),
        f"Bookmark contract {uuid4().hex[:12]}",
        "A temporary thread for bookmark contract verification.",
    )
    assert created.status_code == 201
    thread_id = int(created.json()["id"])

    try:
        added = auth_api_client.bookmark_thread(thread_id)
        assert added.status_code == 200
        assert added.json()["bookmarked"] is True

        repeated = auth_api_client.bookmark_thread(thread_id)
        assert repeated.status_code == 200
        assert repeated.json()["bookmarked"] is True

        status = auth_api_client.get_thread_bookmark(thread_id)
        assert status.status_code == 200
        assert status.json()["bookmarked"] is True

        page = auth_api_client.list_bookmarks(page=1, size=999)
        assert page.status_code == 200
        assert page.json()["size"] == 20
        assert any(item["threadId"] == thread_id for item in page.json()["items"])

        removed = auth_api_client.unbookmark_thread(thread_id)
        assert removed.status_code == 200
        assert removed.json()["bookmarked"] is False
        assert auth_api_client.get_thread_bookmark(thread_id).json()["bookmarked"] is False
    finally:
        deleted = auth_api_client.delete_thread(thread_id)
        assert deleted.status_code == 204
