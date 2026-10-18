"""Thread API contracts against the deterministic CI seed."""

from __future__ import annotations

import pytest

from tests.api.conftest import Seed, json_body, require_status


@pytest.mark.api
@pytest.mark.p0
def test_like_and_unlike_thread(ci_seed: Seed) -> None:
    liked = ci_seed.owner.like_thread(ci_seed.thread_id)
    require_status(liked, (200,), "thread like")
    assert json_body(liked, "thread like").get("liked") is True
    try:
        unliked = ci_seed.owner.unlike_thread(ci_seed.thread_id)
        require_status(unliked, (200,), "thread unlike")
        assert json_body(unliked, "thread unlike").get("liked") is False
    finally:
        ci_seed.owner.unlike_thread(ci_seed.thread_id)
