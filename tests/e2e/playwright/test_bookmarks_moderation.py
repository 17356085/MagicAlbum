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
        auth_api_client.unbookmark_thread(thread_id)
        auth_api_client.delete_thread(thread_id)


@pytest.mark.ui
def test_report_dialog_and_admin_hide(
    authenticated_page: Page, auth_api_client: ApiClient
) -> None:
    if auth_api_client.get_admin_status().status_code != 200:
        pytest.skip("APP_ADMIN_EMAIL 未指向该测试账号")

    thread_id = create_thread(auth_api_client, f"Moderation browser {uuid4().hex[:10]}")
    page = authenticated_page
    page.goto(f"{BASE_URL}/threads/{thread_id}")
    page.get_by_role("button", name="举报", exact=True).click()
    dialog = page.get_by_role("dialog", name="举报内容")
    expect(dialog).to_be_visible()
    dialog.get_by_label("补充说明").fill("浏览器端审核闭环验证")
    dialog.get_by_role("button", name="提交举报").click()
    expect(dialog.get_by_role("status")).to_contain_text("举报已提交")

    page.goto(f"{BASE_URL}/admin/reports")
    expect(page.get_by_role("heading", name="举报审核")).to_be_visible()
    report_row = page.locator("li").filter(has_text="浏览器端审核闭环验证")
    expect(report_row).to_be_visible()
    page.once("dialog", lambda confirmation: confirmation.accept())
    report_row.get_by_role("button", name="隐藏内容").click()
    expect(page.get_by_role("status").filter(has_text="隐藏内容成功")).to_be_visible()
    assert auth_api_client.get_thread(thread_id).status_code == 404
