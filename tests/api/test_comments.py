"""Comment API contracts against the deterministic CI seed."""

from __future__ import annotations

from uuid import uuid4

import pytest

from tests.api.conftest import Seed, items, json_body, require_id, require_status


@pytest.mark.api
@pytest.mark.p0
def test_create_post(ci_seed: Seed) -> None:
    response = ci_seed.owner.create_post(
        ci_seed.thread_id,
        f"CI API extra comment {uuid4().hex[:12]}",
    )
    require_status(response, (201,), "comment creation")
    post_id = require_id(json_body(response, "comment creation"), "comment creation")
    try:
        assert post_id > 0
    finally:
        require_status(ci_seed.owner.delete_post(post_id), (204,), "created comment cleanup")


@pytest.mark.api
@pytest.mark.p0
def test_list_posts(ci_seed: Seed) -> None:
    response = ci_seed.owner.list_posts(ci_seed.thread_id, page=1, size=20)
    require_status(response, (200,), "comment listing")
    post_ids = {int(item.get("id") or 0) for item in items(json_body(response, "comment listing"))}
    assert ci_seed.post_id in post_ids


@pytest.mark.api
@pytest.mark.p0
def test_delete_own_post(ci_seed: Seed) -> None:
    response = ci_seed.reader.create_post(
        ci_seed.thread_id,
        f"CI API delete comment {uuid4().hex[:12]}",
    )
    require_status(response, (201,), "deletable comment creation")
    post_id = require_id(json_body(response, "deletable comment creation"), "deletable comment creation")
    require_status(ci_seed.reader.delete_post(post_id), (204,), "own comment deletion")


@pytest.mark.api
@pytest.mark.p0
def test_like_and_unlike_post(ci_seed: Seed) -> None:
    liked = ci_seed.owner.like_post(ci_seed.post_id)
    require_status(liked, (200,), "comment like")
    assert json_body(liked, "comment like").get("liked") is True
    try:
        unliked = ci_seed.owner.unlike_post(ci_seed.post_id)
        require_status(unliked, (200,), "comment unlike")
        assert json_body(unliked, "comment unlike").get("liked") is False
    finally:
        # The endpoint is idempotent, so cleanup remains safe if the assertion
        # above fails after the like was persisted.
        ci_seed.owner.unlike_post(ci_seed.post_id)
