"""
帖子模块测试 — Selenium 版本

涵盖：发现页浏览、筛选、分页、创建帖子、详情页、点赞

Selenium 学习要点 — 列表页测试：
  1. 数据驱动的页面：内容来自数据库，测试结果依赖数据
  2. 需要有"种子数据"或使用 fixture 创建测试数据
  3. 空状态/边界条件需要特殊场景
"""

from urllib.parse import parse_qs, urlparse

import pytest
from selenium.webdriver.remote.webdriver import WebDriver

from tests.api.api_client import ApiClient
from tests.e2e.selenium.pages.discover_page import DiscoverPage
from tests.e2e.selenium.pages.thread_create_page import ThreadCreatePage
from tests.e2e.selenium.pages.thread_detail_page import ThreadDetailPage
from tests.e2e.selenium.pages.auth_modal_page import AuthModalPage
from tests.e2e.selenium.config.settings import settings
from tests.utils.data_generator import unique_thread_title, unique_thread_content


def _items(response) -> list[dict]:
    body = response.json()
    nested = body.get("data") if isinstance(body, dict) else None
    payload = nested if isinstance(nested, dict) else body
    values = payload.get("items", []) if isinstance(payload, dict) else []
    return [item for item in values if isinstance(item, dict)]


def _live_section_id() -> int:
    client = ApiClient(settings.API_BASE_URL)
    try:
        response = client.list_sections(page=1, size=100)
        assert response.status_code == 200
        sections = _items(response)
        assert sections, "隔离测试栈必须返回至少一个真实分区"
        return int(sections[0]["id"])
    finally:
        client.close()


def _live_tag() -> str:
    client = ApiClient(settings.API_BASE_URL)
    try:
        response = client.list_popular_tags(size=20)
        assert response.status_code == 200
        tags = _items(response)
        assert tags, "隔离测试栈必须返回至少一个真实标签"
        name = tags[0].get("name")
        assert isinstance(name, str) and name.strip()
        return name.strip()
    finally:
        client.close()


# ═══════════════════════════════════════════════════════════════
# 发现页测试
# ═══════════════════════════════════════════════════════════════

class TestDiscoverPage:
    """发现页 UI 测试"""

    @pytest.mark.smoke
    @pytest.mark.ui
    def test_discover_loads_successfully(self, clean_page: WebDriver) -> None:
        """
        烟雾测试：发现页正常加载
        验证：显示帖子列表或空状态，不应显示异常错误

        Selenium 学习要点 — 烟雾测试：
          烟雾测试（Smoke Test）是最基础的验证，
          确保页面能加载、不白屏、不报错。
          通常只验证关键元素存在即可。
        """
        discover = DiscoverPage(clean_page)
        discover.go()
        discover.wait_for_content()

        # 页面应该正常加载（有帖子或显示空状态）
        has_content = discover.has_threads() or discover.is_empty()
        assert has_content, "发现页应该显示内容或空状态"

        # 不应该有错误消息
        error = discover.get_error_text()
        assert error is None, f"不应显示错误消息，实际: {error}"

    @pytest.mark.p0
    @pytest.mark.ui
    def test_discover_pagination(self, clean_page: WebDriver) -> None:
        """
        分页导航测试
        验证：有足够帖子时分页功能正常

        Selenium 学习要点 — 条件断言：
          分页只在总帖子数 > 每页数量时才显示。
          使用条件断言：if 有分页 then 验证翻页功能正确。
        """
        discover = DiscoverPage(clean_page)
        discover.go()
        discover.wait_for_content()

        assert discover.has_threads(), "全量回归 fixture 必须提供帖子以验证分页"
        assert discover.has_pagination(), "全量回归 fixture 必须提供超过一页的帖子"
        initial_page = discover.get_current_page()
        discover.go_next_page()
        new_page = discover.get_current_page()

        assert new_page == initial_page + 1, (
            f"翻页后应为第 {initial_page + 1} 页，实际第 {new_page} 页"
        )

    @pytest.mark.p0
    @pytest.mark.ui
    def test_discover_filter_by_section(self, clean_page: WebDriver) -> None:
        """
        分区筛选测试
        验证：URL 包含 sectionId 参数
        """
        section_id = _live_section_id()
        discover = DiscoverPage(clean_page)
        discover.go_with_query(sectionId=section_id, page=1)
        discover.wait_for_content()

        assert f"sectionId={section_id}" in clean_page.current_url, (
            f"URL 应包含真实 sectionId={section_id}，实际: {clean_page.current_url}"
        )

    @pytest.mark.p1
    @pytest.mark.ui
    def test_discover_filter_by_tag(self, clean_page: WebDriver) -> None:
        """
        标签筛选测试
        验证：URL 包含 tag 参数
        """
        tag = _live_tag()
        discover = DiscoverPage(clean_page)
        discover.go_with_query(tag=tag, page=1)
        discover.wait_for_content()

        query = parse_qs(urlparse(clean_page.current_url).query)
        assert query.get("tag") == [tag], (
            f"URL 应包含真实 tag={tag}，实际: {clean_page.current_url}"
        )

    @pytest.mark.p0
    @pytest.mark.ui
    def test_click_thread_navigates_to_detail(self, clean_page: WebDriver) -> None:
        """
        点击帖子卡片跳转详情页
        验证：URL 变为 /threads/:id

        Selenium 学习要点 — 页面跳转断言：
          点击链接后 URL 会变化，用 WebDriverWait 等待 URL
          匹配目标模式，而不是简单的 time.sleep()。
        """
        discover = DiscoverPage(clean_page)
        discover.go()
        discover.wait_for_content()

        assert discover.has_threads(), "全量回归 fixture 必须提供帖子以验证详情跳转"

        discover.click_thread(0)
        assert "/threads/" in clean_page.current_url, (
            f"应跳转到帖子详情，实际: {clean_page.current_url}"
        )

    @pytest.mark.ui
    def test_discover_empty_state_with_nonexistent_tag(self, clean_page: WebDriver) -> None:
        """
        空状态测试 — 搜索不存在的标签
        验证：显示空状态
        """
        discover = DiscoverPage(clean_page)
        discover.go_with_query(tag="xyz_nonexistent_tag_999999", page=1)
        discover.wait_for_content()

        # 应该显示空状态或 0 个帖子
        assert discover.is_empty() or discover.get_thread_count() == 0, (
            "不存在的标签应返回空结果"
        )


# ═══════════════════════════════════════════════════════════════
# 创建帖子测试（需要登录）
# ═══════════════════════════════════════════════════════════════

class TestThreadCreate:
    """创建帖子 UI 测试"""

    @pytest.mark.p0
    @pytest.mark.ui
    @pytest.mark.auth
    def test_create_thread_successfully(
        self,
        auth_page: WebDriver,
        auth_api_client: ApiClient,
        auth_session: dict,
    ) -> None:
        """
        登录后成功创建帖子

        Selenium 学习要点 — 完整表单提交流程：
          1. 导航到创建页
          2. 填写表单（select + input + textarea）
          3. 等待可用按钮并提交
          4. 验证成功反馈中的真实帖子链接
          5. 通过同一认证 API 客户端读回持久化结果
        """
        create = ThreadCreatePage(auth_page)
        create.go()
        create.wait_for_form()

        # 如果显示登录提示，说明认证失败
        assert not create.is_login_prompt_shown(), "全量回归认证账号必须能打开创建帖子表单"

        # 填写并提交
        title = unique_thread_title()
        content = unique_thread_content()
        create.fill_form(title=title, content=content, tags="测试, 自动化")
        thread_id = None
        try:
            submit_button = create.wait_for_submit_enabled()
            assert submit_button.is_enabled(), "填写有效内容后发布按钮必须处于 enabled 状态"
            submit_button.click()

            success = create.wait_for_success()
            assert success.text.strip() == "发布成功", "必须显示服务端创建成功状态"

            thread_link = create.wait_for_created_thread_link()
            href = thread_link.get_attribute("href") or ""
            path_parts = [part for part in urlparse(href).path.split("/") if part]
            assert len(path_parts) == 2 and path_parts[0] == "threads" and path_parts[1].isdigit(), (
                f"成功反馈必须包含真实帖子 href，实际: {href}"
            )
            thread_id = int(path_parts[1])
            assert thread_id > 0, f"帖子 ID 必须为正整数，实际: {thread_id}"

            user = auth_session["user"]
            expected_author_id = int(user.get("id") or user.get("userId") or 0)
            assert expected_author_id > 0, "认证 fixture 必须提供有效用户 ID"

            readback = auth_api_client.get_thread(thread_id)
            assert readback.status_code == 200, (
                f"创建后的帖子必须可由同认证 API 客户端读回，实际 HTTP {readback.status_code}"
            )
            body = readback.json()
            thread_body = (
                body.get("data")
                if isinstance(body, dict) and isinstance(body.get("data"), dict)
                else body
            )
            assert isinstance(thread_body, dict), "帖子读回响应必须为对象"
            assert int(thread_body.get("id") or 0) == thread_id, (
                f"读回帖子 ID 不匹配，期望 {thread_id}，实际 {thread_body.get('id')}"
            )
            assert int(thread_body.get("authorId") or 0) == expected_author_id, (
                "读回帖子作者必须是当前认证用户"
            )
            assert thread_body.get("title") == title, "读回帖子标题必须与提交内容一致"
            assert thread_body.get("content") == content, "读回帖子正文必须与提交内容一致"
        finally:
            if thread_id is not None:
                cleanup = auth_api_client.delete_thread(thread_id)
                assert cleanup.status_code == 204, (
                    f"只允许清理本测试创建的帖子，且删除必须成功，实际 HTTP {cleanup.status_code}"
                )

    @pytest.mark.p1
    @pytest.mark.ui
    @pytest.mark.auth
    def test_create_thread_empty_form_validation(self, auth_page: WebDriver) -> None:
        """
        空表单提交验证
        验证：显示错误或按钮 disabled

        Selenium 学习要点 — 表单验证：
          前端验证有两种方式：
            - 提交按钮 disabled（表单无效时）
            - 提交后显示错误消息
          这个测试覆盖两者。
        """
        create = ThreadCreatePage(auth_page)
        create.go()
        create.wait_for_form()

        assert not create.is_login_prompt_shown(), "全量回归认证账号必须能打开创建帖子表单"

        # 不填内容直接提交
        create.submit()

        import time
        time.sleep(0.5)

        # 应该有错误反馈
        error_shown = create.is_error_shown()
        btn_disabled = create.is_submit_disabled()

        assert error_shown or btn_disabled, (
            f"空表单应有验证反馈(error={error_shown}, disabled={btn_disabled})"
        )


# ═══════════════════════════════════════════════════════════════
# 帖子详情测试
# ═══════════════════════════════════════════════════════════════

class TestThreadDetail:
    """帖子详情 UI 测试"""

    @pytest.mark.ui
    def test_thread_not_found(self, clean_page: WebDriver) -> None:
        """
        访问不存在的帖子
        验证：显示"未找到"之类的提示
        """
        detail = ThreadDetailPage(clean_page)
        detail.go(99999999)  # 不存在的ID
        detail.wait_for_content()

        assert detail.is_not_found(), (
            f"不存在的帖子应显示未找到，当前URL: {clean_page.current_url}"
        )

    @pytest.mark.p0
    @pytest.mark.ui
    def test_thread_detail_loads_from_discover(self, clean_page: WebDriver) -> None:
        """
        从发现页点击进入帖子详情
        验证：详情页正常加载
        """
        discover = DiscoverPage(clean_page)
        discover.go()
        discover.wait_for_content()

        assert discover.has_threads(), "全量回归 fixture 必须提供帖子可点击"

        discover.click_thread(0)

        detail = ThreadDetailPage(clean_page)
        detail.wait_for_content()

        assert detail.is_loaded(), "帖子详情页应该正常加载"
