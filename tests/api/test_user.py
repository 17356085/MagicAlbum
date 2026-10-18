"""User follow and profile API contracts against the deterministic CI seed."""

from __future__ import annotations

import pytest

from tests.api.conftest import Seed, items, json_body, require_status


def _follow(seed: Seed) -> None:
    require_status(seed.owner.follow_user(seed.reader_id), (200,), "follow creation")


def _unfollow(seed: Seed) -> None:
    require_status(seed.owner.unfollow_user(seed.reader_id), (200,), "follow cleanup")


@pytest.mark.api
@pytest.mark.p0
def test_follow_and_unfollow_user(ci_seed: Seed) -> None:
    _follow(ci_seed)
    try:
        following = ci_seed.owner.get_user_following(ci_seed.owner_id)
        require_status(following, (200,), "following readback")
        assert any(int(item.get("id") or 0) == ci_seed.reader_id for item in items(json_body(following, "following readback")))
    finally:
        _unfollow(ci_seed)


@pytest.mark.api
@pytest.mark.p0
def test_get_user_following(ci_seed: Seed) -> None:
    _follow(ci_seed)
    try:
        response = ci_seed.owner.get_user_following(ci_seed.owner_id)
        require_status(response, (200,), "following listing")
        assert any(int(item.get("id") or 0) == ci_seed.reader_id for item in items(json_body(response, "following listing")))
    finally:
        _unfollow(ci_seed)


@pytest.mark.api
@pytest.mark.p0
def test_get_user_followers(ci_seed: Seed) -> None:
    _follow(ci_seed)
    try:
        response = ci_seed.reader.get_user_followers(ci_seed.reader_id)
        require_status(response, (200,), "followers listing")
        assert any(int(item.get("id") or 0) == ci_seed.owner_id for item in items(json_body(response, "followers listing")))
    finally:
        _unfollow(ci_seed)
