"""Deterministic API contracts for the isolated CI Compose stack."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
import secrets

from tests.api.conftest import (
    Identity,
    Seed,
    api_base_url,
    items,
    json_body,
    new_identity,
    payload,
    require_id,
    require_status,
)
from tests.api.api_client import ApiClient

import pytest


@pytest.mark.api
@pytest.mark.p0
def test_ci_seed_readback(ci_seed: Seed) -> None:
    """Verify that registration, authentication, thread, and comment persist."""

    current_user = ci_seed.owner.get_current_user()
    require_status(current_user, (200,), "owner current-user readback")
    assert require_id(
        payload(json_body(current_user, "owner current-user readback")),
        "owner current-user readback",
    ) == ci_seed.owner_id

    thread = ci_seed.reader.get_thread(ci_seed.thread_id)
    require_status(thread, (200,), "thread readback")
    thread_body = json_body(thread, "thread readback")
    assert thread_body.get("id") == ci_seed.thread_id
    assert thread_body.get("title") == ci_seed.thread_title
    assert thread_body.get("content") == ci_seed.thread_content
    assert int(thread_body.get("authorId") or 0) == ci_seed.owner_id

    posts = ci_seed.owner.list_posts(ci_seed.thread_id, page=1, size=20)
    require_status(posts, (200,), "comment readback")
    matching = [
        item
        for item in items(json_body(posts, "comment readback"))
        if int(item.get("id") or 0) == ci_seed.post_id
    ]
    assert matching and matching[0].get("content") == ci_seed.comment_content
    assert int(matching[0].get("authorId") or 0) == ci_seed.reader_id


@pytest.mark.api
@pytest.mark.p0
def test_ci_seed_interactions_are_persistent(ci_seed: Seed) -> None:
    """Verify bookmark and like transitions with authenticated readbacks."""

    bookmark = ci_seed.owner.bookmark_thread(ci_seed.thread_id)
    require_status(bookmark, (200,), "bookmark creation")
    assert json_body(bookmark, "bookmark creation").get("bookmarked") is True
    bookmark_readback = ci_seed.owner.get_thread_bookmark(ci_seed.thread_id)
    require_status(bookmark_readback, (200,), "bookmark readback")
    assert json_body(bookmark_readback, "bookmark readback").get("bookmarked") is True
    removed = ci_seed.owner.unbookmark_thread(ci_seed.thread_id)
    require_status(removed, (200,), "bookmark removal")
    assert json_body(removed, "bookmark removal").get("bookmarked") is False

    liked = ci_seed.reader.like_thread(ci_seed.thread_id)
    require_status(liked, (200,), "thread like")
    assert json_body(liked, "thread like").get("liked") is True
    like_readback = ci_seed.reader.get_thread_like(ci_seed.thread_id)
    require_status(like_readback, (200,), "thread-like readback")
    assert json_body(like_readback, "thread-like readback").get("liked") is True
    unliked = ci_seed.reader.unlike_thread(ci_seed.thread_id)
    require_status(unliked, (200,), "thread unlike")
    assert json_body(unliked, "thread unlike").get("liked") is False


@pytest.mark.api
@pytest.mark.p0
def test_ci_seed_ownership_is_enforced(ci_seed: Seed) -> None:
    """Verify that each generated account can only mutate its own content."""

    require_status(
        ci_seed.reader.update_thread(ci_seed.thread_id, {"title": "forbidden-ci-update"}),
        (403,),
        "cross-user thread update",
    )
    require_status(
        ci_seed.reader.delete_thread(ci_seed.thread_id),
        (403,),
        "cross-user thread delete",
    )
    require_status(
        ci_seed.owner.update_post(ci_seed.post_id, {"contentMd": "forbidden-ci-update"}),
        (403,),
        "cross-user comment update",
    )
    require_status(
        ci_seed.owner.delete_post(ci_seed.post_id),
        (403,),
        "cross-user comment delete",
    )


@pytest.mark.api
@pytest.mark.p0
def test_ci_anonymous_write_is_rejected(ci_seed: Seed) -> None:
    """Verify the gateway rejects an unauthenticated write request."""

    from tests.api.api_client import ApiClient

    anonymous = ApiClient(ci_seed.owner.base_url)
    try:
        current_user = anonymous.get_current_user()
        require_status(current_user, (401, 403), "anonymous current-user request")
        create = anonymous.create_post(ci_seed.thread_id, "anonymous write")
        require_status(create, (401, 403), "anonymous comment write")
    finally:
        anonymous.close()


@pytest.mark.api
@pytest.mark.p0
def test_ci_concurrent_phone_registration_conflict_is_safe(request: pytest.FixtureRequest) -> None:
    """Exercise the real unique-phone conflict and read back its winner."""

    shared_phone = f"13{secrets.randbelow(10**9):09d}"
    identity_a = replace(new_identity("racea"), phone=shared_phone)
    identity_b = replace(new_identity("raceb"), phone=shared_phone)
    base_url = api_base_url(request.config)

    def register_once(identity: Identity) -> tuple[int, str, Identity]:
        client = ApiClient(base_url)
        try:
            response = client.register(
                username=identity.username,
                email=identity.email,
                phone=identity.phone,
                password=identity.password,
            )
            return response.status_code, response.text, identity
        finally:
            client.close()

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(executor.map(register_once, (identity_a, identity_b)))

    assert sorted(status for status, _, _ in results) == [201, 409]
    sql_markers = (
        "select ",
        "insert ",
        "update ",
        "delete ",
        "sqlstate",
        "jdbc",
        "mysql",
        "constraint",
        "duplicate entry",
    )
    for status, response_text, _ in results:
        if status != 409:
            continue
        lowered = response_text.lower()
        if shared_phone in response_text:
            pytest.fail("duplicate-phone response leaked the submitted phone")
        if any(marker in lowered for marker in sql_markers):
            pytest.fail("duplicate-phone response leaked database details")

    winner = next(identity for status, _, identity in results if status == 201)
    client = ApiClient(base_url)
    try:
        login = client.login_email_password(winner.email, winner.password)
        require_status(login, (200,), "concurrent-registration winner login")
        login_data = payload(json_body(login, "concurrent-registration winner login"))
        token = login_data.get("accessToken") or login_data.get("access_token") or login_data.get("token")
        if not isinstance(token, str) or not token:
            pytest.fail("concurrent-registration winner login did not return an access token")
        client.set_token(token)
        winner_id = require_id(login_data.get("user", login_data), "concurrent-registration winner login")

        current = client.get_current_user()
        require_status(current, (200,), "concurrent-registration current-user readback")
        assert require_id(
            payload(json_body(current, "concurrent-registration current-user readback")),
            "concurrent-registration current-user readback",
        ) == winner_id

        profile = client.get_user_profile(winner_id)
        require_status(profile, (200,), "concurrent-registration profile readback")
        assert require_id(
            payload(json_body(profile, "concurrent-registration profile readback")),
            "concurrent-registration profile readback",
        ) == winner_id
    finally:
        client.close()
