"""Account-persistent bookmark API contract against the deterministic CI seed."""

from __future__ import annotations

import pytest

from tests.api.conftest import Seed, json_body, require_status


@pytest.mark.api
@pytest.mark.p0
def test_bookmark_round_trip_and_effective_page_size(ci_seed: Seed) -> None:
    added = ci_seed.owner.bookmark_thread(ci_seed.thread_id)
    require_status(added, (200,), "bookmark creation")
    assert json_body(added, "bookmark creation").get("bookmarked") is True
    try:
        repeated = ci_seed.owner.bookmark_thread(ci_seed.thread_id)
        require_status(repeated, (200,), "repeated bookmark")
        assert json_body(repeated, "repeated bookmark").get("bookmarked") is True

        status = ci_seed.owner.get_thread_bookmark(ci_seed.thread_id)
        require_status(status, (200,), "bookmark status")
        assert json_body(status, "bookmark status").get("bookmarked") is True

        page = ci_seed.owner.list_bookmarks(page=1, size=999)
        require_status(page, (200,), "bookmark listing")
        body = json_body(page, "bookmark listing")
        assert body.get("size") == 20
        assert any(item.get("threadId") == ci_seed.thread_id for item in body.get("items", []))
    finally:
        removed = ci_seed.owner.unbookmark_thread(ci_seed.thread_id)
        require_status(removed, (200,), "bookmark removal")
        assert json_body(removed, "bookmark removal").get("bookmarked") is False
