"""
页头 Page Object

组件: AppHeader.vue, BrandBar, SearchBox, AuthEntry, UserMenu
功能: 品牌栏、搜索框、登录入口、用户菜单
"""

from playwright.sync_api import Locator, Page

from tests.e2e.playwright.pages.base_page import BasePage


class HeaderPage(BasePage):
    """页头"""

    def __init__(self, page: Page, base_url: str | None = None):
        super().__init__(page, base_url)

    # ── 定位器 ────────────────────────────────────────────────

    @property
    def header(self) -> Locator:
        return self.page.locator("header.community-header")

    @property
    def brand_link(self) -> Locator:
        """品牌/Logo链接，通常回到首页"""
        return self.header.locator("a[href='/discover']").first

    @property
    def search_input(self) -> Locator:
        """搜索框"""
        return self.header.get_by_placeholder("搜索帖子标题或内容", exact=True)

    @property
    def login_entry_btn(self) -> Locator:
        """登录入口按钮（未登录时显示）"""
        return self.header.get_by_role("button", name="登录")

    @property
    def user_menu_trigger(self) -> Locator:
        """已登录账户操作按钮"""
        return self.header.get_by_role("button", name="登出")

    @property
    def user_avatar(self) -> Locator:
        """用户头像"""
        return self.header.locator("img.rounded-full, img.h-8.w-8").first

    @property
    def create_thread_btn(self) -> Locator:
        """发帖按钮"""
        return self.header.get_by_role("link", name="发帖")

    # ── 操作 ──────────────────────────────────────────────────

    def click_login(self) -> None:
        """点击登录按钮，触发弹窗"""
        self.login_entry_btn.click()

    def search(self, keyword: str) -> None:
        """执行搜索"""
        self.search_input.fill(keyword)
        self.search_input.press("Enter")
        self.page.wait_for_function(
            "value => location.pathname === '/discover' && new URLSearchParams(location.search).get('q') === value",
            arg=keyword,
            timeout=10000,
        )

    def click_user_menu(self) -> None:
        """打开用户菜单"""
        self.user_menu_trigger.click()

    def is_logged_in(self) -> bool:
        """判断是否已登录（通过头像是否可见）"""
        return self.user_avatar.is_visible()

    def is_login_entry_visible(self) -> bool:
        return self.login_entry_btn.is_visible()
