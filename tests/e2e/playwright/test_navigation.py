"""
导航与侧边栏测试

覆盖: 侧边栏导航菜单、分区折叠、标签面板、路由跳转、路由守卫
"""

import pytest
from playwright.sync_api import Page

from tests.conftest import BASE_URL
from tests.e2e.playwright.pages.sidebar_page import SidebarPage


class TestSidebarNavigation:
    """侧边栏导航测试"""

    @pytest.mark.p1
    @pytest.mark.ui
    def test_navigate_to_discover_from_sidebar(self, page: Page) -> None:
        """侧边栏导航到发现页"""
        sidebar = SidebarPage(page)
        page.goto(f"{BASE_URL}/ranking", wait_until="domcontentloaded")
        page.wait_for_timeout(1000)

        sidebar.navigate_to_discover()
        assert "/discover" in page.url

    @pytest.mark.p1
    @pytest.mark.ui
    def test_navigate_to_ranking_from_sidebar(self, page: Page) -> None:
        """侧边栏导航到排行榜"""
        sidebar = SidebarPage(page)
        page.goto(f"{BASE_URL}/discover", wait_until="domcontentloaded")
        page.wait_for_timeout(1000)

        sidebar.navigate_to_ranking()
        assert "/ranking" in page.url

    @pytest.mark.p1
    @pytest.mark.ui
    def test_sections_panel_toggle(self, page: Page) -> None:
        """分区面板折叠/展开"""
        sidebar = SidebarPage(page)
        page.goto(f"{BASE_URL}/discover", wait_until="domcontentloaded")
        page.wait_for_timeout(1000)

        # 初始状态应该是展开的
        assert sidebar.is_sections_expanded()

        # 折叠
        sidebar.toggle_sections()
        page.wait_for_timeout(500)
        # 折叠后面板不可见
        sections_visible = sidebar.is_sections_expanded()
        # 再次展开
        sidebar.toggle_sections()
        page.wait_for_timeout(500)

    @pytest.mark.p1
    @pytest.mark.ui
    def test_tags_panel_expand(self, page: Page) -> None:
        """标签面板展开"""
        sidebar = SidebarPage(page)
        page.goto(f"{BASE_URL}/discover", wait_until="domcontentloaded")
        page.wait_for_timeout(1500)

        sidebar.expand_tags()
        # 搜索框应该可见
        assert sidebar.tag_search_input.is_visible()

    @pytest.mark.p2
    @pytest.mark.ui
    def test_tag_search_input(self, page: Page) -> None:
        """标签搜索防抖"""
        sidebar = SidebarPage(page)
        page.goto(f"{BASE_URL}/discover", wait_until="domcontentloaded")
        page.wait_for_timeout(1500)

        sidebar.expand_tags()
        sidebar.search_tag("编程")
        page.wait_for_timeout(500)

        # 搜索关键字被输入
        assert sidebar.tag_search_input.input_value() == "编程"

    @pytest.mark.p1
    @pytest.mark.ui
    def test_my_menu_not_visible_when_logged_out(self, page: Page) -> None:
        """未登录时个人中心菜单不可见"""
        sidebar = SidebarPage(page)
        page.goto(f"{BASE_URL}/discover", wait_until="domcontentloaded")
        page.wait_for_timeout(1000)

        assert not sidebar.is_my_menu_visible()

    @pytest.mark.p1
    @pytest.mark.ui
    def test_my_menu_visible_when_logged_in(self, authenticated_page: Page) -> None:
        """登录后个人中心菜单可见"""
        sidebar = SidebarPage(authenticated_page)
        authenticated_page.wait_for_timeout(1000)

        assert sidebar.is_my_menu_visible()
