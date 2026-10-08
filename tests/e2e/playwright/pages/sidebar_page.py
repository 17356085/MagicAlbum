"""
侧边栏 Page Object

组件: SidebarLeft.vue
功能: 主导航、分区折叠、标签面板、关注动态、最近浏览、个人中心
"""

from playwright.sync_api import Locator, Page

from tests.e2e.playwright.pages.base_page import BasePage


class SidebarPage(BasePage):
    """左侧边栏"""

    def __init__(self, page: Page, base_url: str | None = None):
        super().__init__(page, base_url)

    # ── 定位器 ────────────────────────────────────────────────

    @property
    def sidebar(self) -> Locator:
        return self.page.locator("aside").first

    # 主导航
    @property
    def nav_sections_btn(self) -> Locator:
        """分区折叠按钮"""
        return self.sidebar.get_by_role("button", name="分区")

    @property
    def nav_discover_link(self) -> Locator:
        return self.sidebar.get_by_role("link", name="发现")

    @property
    def nav_ranking_link(self) -> Locator:
        return self.sidebar.get_by_role("link", name="排行榜")

    @property
    def nav_feed_link(self) -> Locator:
        return self.sidebar.get_by_role("link", name="动态")

    @property
    def nav_my_threads_link(self) -> Locator:
        return self.sidebar.get_by_role("link", name="我的帖子")

    @property
    def nav_my_posts_link(self) -> Locator:
        return self.sidebar.get_by_role("link", name="我的评论")

    @property
    def nav_settings_link(self) -> Locator:
        return self.sidebar.get_by_role("link", name="设置")

    # 分区子菜单
    @property
    def sections_submenu(self) -> Locator:
        """分区子菜单容器"""
        return self.sidebar.locator(".grid.overflow-hidden").first

    @property
    def section_links(self) -> Locator:
        """分区链接列表"""
        return self.sections_submenu.locator("a")

    # 标签面板
    @property
    def tags_panel(self) -> Locator:
        """标签面板容器"""
        return self.sidebar.locator(".rounded-xl.border").nth(1)

    @property
    def tags_expand_btn(self) -> Locator:
        """更多标签/收起标签按钮"""
        return self.sidebar.get_by_role("button", name="更多标签")

    @property
    def tag_search_input(self) -> Locator:
        """标签搜索输入框"""
        return self.sidebar.get_by_placeholder("搜索标签")

    @property
    def tag_items(self) -> Locator:
        """标签链接列表"""
        return self.tags_panel.locator("a[href*='tag=']")

    # 动态面板
    @property
    def feed_panel(self) -> Locator:
        """关注动态面板"""
        return self.sidebar.locator("text=动态").first.locator("..")

    @property
    def feed_items(self) -> Locator:
        """动态条目"""
        return self.sidebar.locator("text=/暂无动态|加载中/")

    # 个人中心
    @property
    def my_menu(self) -> Locator:
        """我的菜单（仅登录可见）"""
        return self.sidebar.locator("text=我的").first.locator("..")

    # ── 操作 ──────────────────────────────────────────────────

    def toggle_sections(self) -> None:
        """展开/折叠分区列表"""
        self.nav_sections_btn.click()
        self.page.wait_for_timeout(400)  # 等待 grid-rows 过渡动画

    def is_sections_expanded(self) -> bool:
        """分区是否展开"""
        return self.sections_submenu.is_visible()

    def navigate_to_discover(self) -> None:
        self.nav_discover_link.click()
        self.page.wait_for_url("**/discover", timeout=10000)

    def navigate_to_ranking(self) -> None:
        self.nav_ranking_link.click()
        self.page.wait_for_url("**/ranking", timeout=10000)

    def navigate_to_feed(self) -> None:
        self.nav_feed_link.click()
        self.page.wait_for_url("**/feed", timeout=10000)

    def navigate_to_my_threads(self) -> None:
        self.nav_my_threads_link.click()
        self.page.wait_for_url("**/my/threads", timeout=10000)

    def navigate_to_settings(self) -> None:
        self.nav_settings_link.click()
        self.page.wait_for_url("**/settings", timeout=10000)

    def expand_tags(self) -> None:
        """展开标签搜索面板"""
        self.tags_expand_btn.click()
        self.page.wait_for_timeout(400)

    def search_tag(self, keyword: str) -> None:
        """搜索标签"""
        self.tag_search_input.fill(keyword)
        self.page.wait_for_timeout(400)  # 等待 250ms 防抖

    def click_tag(self, tag_name: str) -> None:
        """点击指定标签"""
        self.tags_panel.get_by_text(f"#{tag_name}").first.click()
        self.page.wait_for_url("**/discover?tag=*", timeout=10000)

    def click_section_in_menu(self, name: str) -> None:
        """在分区子菜单中点击指定分区"""
        self.sections_submenu.get_by_text(name).click()
        self.page.wait_for_url("**/discover?sectionId=*", timeout=10000)

    # ── 状态 ──────────────────────────────────────────────────

    def is_my_menu_visible(self) -> bool:
        """个人中心菜单是否可见（需登录）"""
        return self.my_menu.is_visible()
