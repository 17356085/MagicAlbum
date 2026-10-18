"""Deterministic Selenium health flow for the isolated CI Compose stack.

The historical Selenium modules use an externally supplied account and often
depend on pre-existing content.  This smoke test owns its account, thread, and
comment so a successful browser run proves a real API/UI/readback path.
"""

from __future__ import annotations

from dataclasses import dataclass
import os
import time
from typing import Any, Iterator

import pytest
from selenium.webdriver.common.by import By
from selenium.webdriver.remote.webdriver import WebDriver
from selenium.webdriver.support.ui import WebDriverWait

from tests.api.api_client import ApiClient
from tests.api.conftest import (
    Identity,
    api_base_url,
    items,
    json_body,
    new_identity,
    payload,
    register,
    require_id,
    require_status,
)
from tests.e2e.selenium.pages.thread_detail_page import ThreadDetailPage


BASE_URL = os.getenv("TEST_BASE_URL", "http://localhost:5173").rstrip("/")


@dataclass
class SeleniumSeed:
    client: ApiClient
    identity: Identity
    token: str
    user: dict[str, Any]
    user_id: int
    thread_id: int
    initial_post_id: int
    initial_comment: str
    browser_comment: str | None = None


def _authenticated_user(body: dict[str, Any], label: str, expected_id: int) -> dict[str, Any]:
    data = payload(body)
    candidate = data.get("user") if isinstance(data.get("user"), dict) else data
    if not isinstance(candidate, dict):
        pytest.fail(f"{label} did not return a user object")
    if require_id(candidate, label) != expected_id:
        pytest.fail(f"{label} returned a different user id")
    # Only the fields needed by the frontend auth store enter browser storage.
    return {
        "id": expected_id,
        "username": str(candidate.get("username") or "ci-selenium"),
    }


def _find_post(client: ApiClient, thread_id: int, content: str, author_id: int) -> int | None:
    response = client.list_posts(thread_id, page=1, size=50)
    if response.status_code != 200:
        return None
    try:
        values = items(json_body(response, "Selenium comment readback"))
    except BaseException:
        return None
    for item in values:
        if item.get("content") == content and int(item.get("authorId") or 0) == author_id:
            try:
                return int(item["id"])
            except (KeyError, TypeError, ValueError):
                return None
    return None


@pytest.fixture
def selenium_ci_seed(request: pytest.FixtureRequest) -> Iterator[SeleniumSeed]:
    """Create and remove the account-owned graph used by the Selenium smoke."""

    client = ApiClient(api_base_url(request.config))
    identity = new_identity("selenium")
    thread_id: int | None = None
    initial_post_id: int | None = None

    try:
        user_id = register(client, identity, "Selenium account registration")
        login = client.login_email_password(identity.email, identity.password)
        require_status(login, (200,), "Selenium account login")
        login_data = payload(json_body(login, "Selenium account login"))
        token = login_data.get("accessToken") or login_data.get("access_token") or login_data.get("token")
        if not isinstance(token, str) or not token:
            pytest.fail("Selenium account login did not return an access token")
        client.set_token(token)
        current = client.get_current_user()
        require_status(current, (200,), "Selenium current-user readback")
        user = _authenticated_user(json_body(current, "Selenium current-user readback"), "Selenium current-user readback", user_id)

        sections = client.list_sections(page=1, size=1)
        require_status(sections, (200,), "Selenium section lookup")
        section_values = items(json_body(sections, "Selenium section lookup"))
        if not section_values:
            pytest.fail("isolated Compose database did not expose a writable section")
        section_id = require_id(section_values[0], "Selenium section lookup")

        thread_response = client.create_thread(
            section_id,
            f"CI Selenium health {identity.username}",
            "CI Selenium health thread content.",
        )
        require_status(thread_response, (201,), "Selenium thread seed")
        thread_id = require_id(payload(json_body(thread_response, "Selenium thread seed")), "Selenium thread seed")

        initial_comment = f"CI Selenium initial comment {identity.username}"
        post_response = client.create_post(thread_id, initial_comment)
        require_status(post_response, (201,), "Selenium comment seed")
        initial_post_id = require_id(payload(json_body(post_response, "Selenium comment seed")), "Selenium comment seed")

        seed = SeleniumSeed(
            client=client,
            identity=identity,
            token=token,
            user=user,
            user_id=user_id,
            thread_id=thread_id,
            initial_post_id=initial_post_id,
            initial_comment=initial_comment,
        )
    except BaseException:
        if initial_post_id is not None:
            try:
                client.delete_post(initial_post_id)
            except Exception:
                pass
        if thread_id is not None:
            try:
                client.delete_thread(thread_id)
            except Exception:
                pass
        client.close()
        raise

    try:
        yield seed
    finally:
        cleanup_errors: list[str] = []
        post_ids: list[int] = [seed.initial_post_id]
        if seed.browser_comment:
            browser_post_id = _find_post(
                seed.client,
                seed.thread_id,
                seed.browser_comment,
                seed.user_id,
            )
            if browser_post_id is not None:
                post_ids.append(browser_post_id)

        for post_id in dict.fromkeys(post_ids):
            try:
                response = seed.client.delete_post(post_id)
                if response.status_code != 204:
                    cleanup_errors.append("Selenium comment cleanup returned an unexpected status")
            except Exception as exc:
                cleanup_errors.append(f"Selenium comment cleanup raised {type(exc).__name__}")
        try:
            response = seed.client.delete_thread(seed.thread_id)
            if response.status_code != 204:
                cleanup_errors.append("Selenium thread cleanup returned an unexpected status")
        except Exception as exc:
            cleanup_errors.append(f"Selenium thread cleanup raised {type(exc).__name__}")
        seed.client.close()
        if cleanup_errors:
            pytest.fail("; ".join(cleanup_errors))


@pytest.mark.p0
@pytest.mark.ui
@pytest.mark.smoke
def test_ci_selenium_thread_comment_flow(driver: WebDriver, selenium_ci_seed: SeleniumSeed) -> None:
    """Read a seeded thread, submit a comment in Selenium, and read it via API."""

    driver.get(BASE_URL)
    driver.execute_script("localStorage.clear(); sessionStorage.clear();")
    driver.execute_script(
        """const state = arguments[0];
        localStorage.setItem('accessToken', state.token);
        localStorage.setItem('currentUser', JSON.stringify(state.user));""",
        {"token": selenium_ci_seed.token, "user": selenium_ci_seed.user},
    )
    stored_token = driver.execute_script("return localStorage.getItem('accessToken');")
    stored_user = driver.execute_script("return localStorage.getItem('currentUser');")
    if not isinstance(stored_token, str) or not stored_token or not isinstance(stored_user, str) or not stored_user:
        pytest.fail("Selenium could not persist the generated authentication state")

    detail = ThreadDetailPage(driver)
    detail.go(selenium_ci_seed.thread_id)
    detail.wait_for_content()
    assert detail.is_loaded(), "seeded thread did not load in Selenium"
    WebDriverWait(driver, 15).until(
        lambda current: selenium_ci_seed.initial_comment
        in current.find_element(By.TAG_NAME, "body").text
    )
    assert detail.get_comment_count() >= 1, "seeded comment card did not render"
    assert detail.like_button.is_displayed(), "thread like control did not render"

    comment = f"CI Selenium browser comment {selenium_ci_seed.identity.username}"
    selenium_ci_seed.browser_comment = comment
    detail.post_comment(comment)
    WebDriverWait(driver, 15).until(
        lambda current: comment in current.find_element(By.TAG_NAME, "body").text
    )

    deadline = time.monotonic() + 20
    browser_post_id: int | None = None
    while time.monotonic() < deadline:
        browser_post_id = _find_post(
            selenium_ci_seed.client,
            selenium_ci_seed.thread_id,
            comment,
            selenium_ci_seed.user_id,
        )
        if browser_post_id is not None:
            break
        time.sleep(0.25)
    assert browser_post_id is not None, "browser comment was not persisted by the API"
