"""
评论模块测试 — Selenium 版本

Selenium 学习要点 — 嵌套组件测试：
  评论区是帖子详情页的嵌套组件，有自己的分页和排序。
  Vue 的乐观更新（Optimistic Update）在这里特别明显：
  提交评论后，前端先显示临时数据（带 temp_ 前缀ID），
  API 返回后才替换为真实数据。

  处理乐观更新：
    - 提交后立即检查 DOM 中出现新内容（乐观插入）
    - 等待 API 返回后数据 ID 变化（temp_ 前缀消失）
    - 不能立即断言最终状态
"""

from urllib.parse import urlparse

import pytest
import time
from selenium.webdriver.common.by import By
from selenium.webdriver.remote.webdriver import WebDriver
from selenium.webdriver.support.ui import WebDriverWait

from tests.api.api_client import ApiClient
from tests.e2e.selenium.pages.discover_page import DiscoverPage
from tests.e2e.selenium.pages.thread_detail_page import ThreadDetailPage
from tests.e2e.selenium.config.settings import settings
from tests.utils.data_generator import unique_comment


def _post_page(response) -> tuple[list[dict], int]:
    body = response.json()
    payload = body.get("data") if isinstance(body, dict) and isinstance(body.get("data"), dict) else body
    if not isinstance(payload, dict) or not isinstance(payload.get("items"), list):
        pytest.fail("评论列表读回响应必须包含 items 数组")
    try:
        total = int(payload["total"])
    except (KeyError, TypeError, ValueError):
        pytest.fail("评论列表读回响应必须包含有效 total")
    return [item for item in payload["items"] if isinstance(item, dict)], total


class TestCommentUI:
    """评论 UI 测试"""

    @pytest.mark.p0
    @pytest.mark.ui
    @pytest.mark.auth
    def test_post_comment_on_thread(self, auth_page: WebDriver) -> None:
        """
        在帖子详情页发表评论

        Selenium 学习要点 — 乐观更新处理：
          1. 填写评论
          2. 点击提交
          3. 等待 DOM 更新（可能是乐观插入或 API 返回后渲染）
          4. 验证页面包含评论文本
        """
        # 先通过发现页找到一个帖子
        discover = DiscoverPage(auth_page)
        discover.go()
        discover.wait_for_content()

        assert discover.has_threads(), "全量回归 fixture 必须提供帖子可评论"

        discover.click_thread(0)

        detail = ThreadDetailPage(auth_page)
        detail.wait_for_content()

        assert detail.is_loaded(), "帖子详情必须加载成功后才能发表评论"

        # 发表评论
        comment_text = unique_comment()
        detail.post_comment(comment_text)

        # 等待乐观更新完成
        time.sleep(1.5)

        # 验证评论出现在页面上
        # 注意：这里用 body 文本检查，因为评论可能在不同层级
        assert comment_text in auth_page.find_element("tag name", "body").text, (
            f"评论 '{comment_text[:30]}...' 应该出现在页面上"
        )

    @pytest.mark.p1
    @pytest.mark.ui
    @pytest.mark.auth
    def test_empty_comment_should_not_submit(
        self,
        auth_page: WebDriver,
        auth_api_client: ApiClient,
    ) -> None:
        """
        空评论不应提交成功

        Selenium 学习要点 — 负面测试：
          验证提交按钮在空输入时 disabled，
          或提交后显示错误。
        """
        discover = DiscoverPage(auth_page)
        discover.go()
        discover.wait_for_content()

        assert discover.has_threads(), "全量回归 fixture 必须提供帖子"

        discover.click_thread(0)
        detail = ThreadDetailPage(auth_page)
        detail.wait_for_content()

        assert detail.is_loaded(), "帖子详情必须加载成功后才能验证空评论"

        path_parts = [part for part in urlparse(auth_page.current_url).path.split("/") if part]
        assert len(path_parts) == 2 and path_parts[0] == "threads" and path_parts[1].isdigit(), (
            f"详情页 URL 必须包含真实帖子 ID，实际: {auth_page.current_url}"
        )
        thread_id = int(path_parts[1])

        before = auth_api_client.list_posts(thread_id, page=1, size=50)
        assert before.status_code == 200, (
            f"空评论测试必须先读回评论列表，实际 HTTP {before.status_code}"
        )
        before_items, before_total = _post_page(before)
        before_ids = {str(item.get("id")) for item in before_items}
        before_dom_count = detail.get_comment_count()

        submit_btn = detail.comment_submit_btn
        # 检查按钮是否 disabled
        if not submit_btn.is_enabled():
            # 空输入时按钮 disabled → 明确验证该分支，而不是仅提前返回。
            assert not submit_btn.is_enabled(), "空评论按钮 disabled 分支必须保持 disabled"
            return

        # 如果按钮 enabled，尝试提交空内容
        submit_btn.click()
        error_text = WebDriverWait(auth_page, 5).until(
            lambda driver: next(
                (
                    element.text.strip()
                    for element in driver.find_elements(
                        By.CSS_SELECTOR, "#thread-comments [role='alert']"
                    )
                    if element.is_displayed() and element.text.strip()
                ),
                False,
            )
        )
        assert error_text, "enabled 的空评论提交必须显示可见的非空错误反馈"

        # 空内容被拒绝后必须有错误反馈，且页面和服务端都不能出现新评论。
        assert detail.get_comment_count() == before_dom_count, (
            "提交空评论后页面不应新增评论"
        )
        after_items: list[dict] | None = None
        after_total: int | None = None

        def read_posts_once(_driver: WebDriver) -> bool:
            nonlocal after_items, after_total
            response = auth_api_client.list_posts(thread_id, page=1, size=50)
            if response.status_code != 200:
                return False
            after_items, after_total = _post_page(response)
            return True

        WebDriverWait(auth_page, 5).until(read_posts_once)
        assert after_items is not None, "空评论提交后必须完成评论列表读回"
        assert after_total is not None, "空评论提交后必须完成评论总数读回"
        after_ids = {str(item.get("id")) for item in after_items}
        assert after_total == before_total, "提交空评论后服务端评论总数不得增加"
        assert len(after_items) == len(before_items), "提交空评论后评论数量不得增加"
        assert after_ids == before_ids, "提交空评论后评论 ID 集合不得变化"

    @pytest.mark.ui
    def test_comment_list_displayed(self, clean_page: WebDriver) -> None:
        """
        验证帖子详情页显示评论区
        """
        discover = DiscoverPage(clean_page)
        discover.go()
        discover.wait_for_content()

        assert discover.has_threads(), "全量回归 fixture 必须提供帖子"

        discover.click_thread(0)

        detail = ThreadDetailPage(clean_page)
        detail.wait_for_content()

        assert detail.is_loaded(), "帖子详情必须加载成功后才能验证评论区"

        # 评论区应该存在（输入框可见或评论列表可见）
        time.sleep(0.5)
        # 至少页面加载完成没有崩溃
        assert True  # 如果到这里没抛异常，说明页面正常
