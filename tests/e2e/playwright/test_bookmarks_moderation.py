"""Real-browser verification for account bookmarks and the local review queue."""

import os
from uuid import uuid4

import pytest
from playwright.sync_api import Page, expect

from tests.api.api_client import ApiClient


BASE_URL = os.getenv("TEST_BASE_URL", "http://127.0.0.1:5173").rstrip("/")


def create_thread(client: ApiClient, title: str) -> int:
    sections = client.list_sections(page=1, size=1)
    assert sections.status_code == 200
    section_id = sections.json()["items"][0]["id"]
    created = client.create_thread(section_id, title, "Local browser verification content.")
    assert created.status_code == 201
    return int(created.json()["id"])


def _items(response) -> list[dict]:
    body = response.json()
    nested = body.get("data") if isinstance(body, dict) else None
    payload = nested if isinstance(nested, dict) else body
    values = payload.get("items", []) if isinstance(payload, dict) else []
    return [item for item in values if isinstance(item, dict)]


def _cleanup_moderation_thread(client: ApiClient, thread_id: int) -> None:
    """Resolve a temporary report before deleting its test thread."""
    errors: list[str] = []
    for status in ("HIDDEN", "PENDING"):
        response = client.list_admin_reports(status=status, page=1, size=50)
        if response.status_code != 200:
            errors.append(f"{status} report cleanup lookup returned HTTP {response.status_code}")
            continue
        for report in _items(response):
            if str(report.get("targetType", "")).upper() != "THREAD":
                continue
            if int(report.get("targetId") or 0) != thread_id:
                continue
            if status == "HIDDEN":
                action = "RESTORE"
                reason = "Full regression cleanup restored the temporary moderation thread."
            else:
                action = "DISMISS"
                reason = "Full regression cleanup dismissed the temporary moderation report."
            resolved = client.resolve_admin_report(
                int(report["id"]),
                action,
                reason,
            )
            if resolved.status_code != 200:
                errors.append(f"{status} report cleanup returned HTTP {resolved.status_code}")
    deleted = client.delete_thread(thread_id)
    if deleted.status_code not in (204, 404):
        errors.append(f"moderation thread cleanup returned HTTP {deleted.status_code}")
    if errors:
        pytest.fail("; ".join(errors))


@pytest.mark.ui
def test_bookmark_button_and_account_list(
    authenticated_page: Page, auth_api_client: ApiClient
) -> None:
    title = f"Bookmark browser {uuid4().hex[:10]}"
    thread_id = create_thread(auth_api_client, title)
    page = authenticated_page
    try:
        page.goto(f"{BASE_URL}/threads/{thread_id}")
        bookmark = page.get_by_role("button", name="收藏", exact=True)
        expect(bookmark).to_be_visible()
        bookmark.click()
        expect(page.get_by_role("button", name="取消收藏")).to_be_visible()

        page.goto(f"{BASE_URL}/my/bookmarks")
        expect(page.get_by_role("heading", name="我的收藏")).to_be_visible()
        bookmarks_section = page.locator("section").filter(
            has=page.get_by_role("heading", name="我的收藏")
        )
        expect(bookmarks_section.get_by_role("link").filter(has_text=title)).to_be_visible()
    finally:
        cleanup_errors: list[str] = []
        try:
            removed = auth_api_client.unbookmark_thread(thread_id)
            if removed.status_code != 200:
                cleanup_errors.append(
                    f"bookmark cleanup returned HTTP {removed.status_code}"
                )
            else:
                removed_body = removed.json()
                if not isinstance(removed_body, dict) or removed_body.get("bookmarked") is not False:
                    cleanup_errors.append("bookmark cleanup did not return bookmarked=false")
        except Exception as exc:
            cleanup_errors.append(f"bookmark cleanup raised {type(exc).__name__}")

        try:
            deleted = auth_api_client.delete_thread(thread_id)
            if deleted.status_code != 204:
                cleanup_errors.append(f"thread cleanup returned HTTP {deleted.status_code}")
        except Exception as exc:
            cleanup_errors.append(f"thread cleanup raised {type(exc).__name__}")

        if cleanup_errors:
            pytest.fail("bookmark test cleanup failed: " + "; ".join(cleanup_errors))


@pytest.mark.ui
def test_report_dialog_and_admin_hide(
    authenticated_page: Page, auth_api_client: ApiClient
) -> None:
    admin_status = auth_api_client.get_admin_status()
    assert admin_status.status_code == 200
    admin_body = admin_status.json()
    assert admin_body.get("admin") is True, "admin endpoint must prove admin=true, not only HTTP 200"

    thread_id = create_thread(auth_api_client, f"Moderation browser {uuid4().hex[:10]}")
    page = authenticated_page
    try:
        page.goto(f"{BASE_URL}/threads/{thread_id}")
        interaction = page.locator("section[aria-label='帖子互动']")
        interaction.get_by_role("button", name="举报", exact=True).click()
        dialog = page.get_by_role("dialog", name="举报内容")
        expect(dialog).to_be_visible()
        dialog.get_by_label("补充说明").fill("浏览器端审核闭环验证")
        dialog.get_by_role("button", name="提交举报").click()
        expect(dialog.get_by_role("status")).to_contain_text("举报已提交")

        page.goto(f"{BASE_URL}/admin/reports")
        expect(page.get_by_role("heading", name="举报审核")).to_be_visible()
        report_row = page.locator("li.ui-list-row").filter(has_text="浏览器端审核闭环验证")
        expect(report_row).to_have_count(1)
        report_row.get_by_role("button", name="隐藏内容", exact=True).click()
        report_row.get_by_label("隐藏内容理由（必填）", exact=True).fill("浏览器端确认违规内容")
        report_row.get_by_role("button", name="确认隐藏内容", exact=True).click()
        expect(page.get_by_role("status").filter(has_text="隐藏内容成功，列表已刷新。")).to_be_visible()
        assert auth_api_client.get_thread(thread_id).status_code == 404

        page.get_by_label("显示状态", exact=True).select_option("HIDDEN")
        page.get_by_role("button", name="应用筛选", exact=True).click()
        report_row = page.locator("li.ui-list-row").filter(has_text="浏览器端审核闭环验证")
        expect(report_row).to_have_count(1)
        report_row.get_by_role("button", name="恢复内容", exact=True).click()
        report_row.get_by_label("恢复内容理由（必填）", exact=True).fill("浏览器端恢复临时审核内容")
        report_row.get_by_role("button", name="确认恢复内容", exact=True).click()
        expect(page.get_by_role("status").filter(has_text="恢复内容成功，列表已刷新。")).to_be_visible()
        assert auth_api_client.get_thread(thread_id).status_code == 200
    finally:
        _cleanup_moderation_thread(auth_api_client, thread_id)
