"""
设置页 Page Object

路由: /settings, /settings/account
功能: 个人资料、通知设置、账号管理、密码修改
"""

from playwright.sync_api import Locator, Page

from tests.e2e.playwright.pages.base_page import BasePage


class SettingsPage(BasePage):
    """设置页"""

    def __init__(self, page: Page, base_url: str | None = None):
        super().__init__(page, base_url)
        self.path = "/settings"

    # ── 定位器 ────────────────────────────────────────────────

    @property
    def profile_tab(self) -> Locator:
        """个人资料Tab"""
        return self.page.get_by_role("button", name="资料设置", exact=True)

    @property
    def notifications_tab(self) -> Locator:
        """通知Tab"""
        return self.page.get_by_role("button", name="通知管理", exact=True)

    @property
    def connected_tab(self) -> Locator:
        """关联账号Tab"""
        return self.page.get_by_role("button", name="第三方关联", exact=True)

    @property
    def account_link(self) -> Locator:
        """账号信息Tab按钮"""
        return self.page.get_by_role("button", name="账号信息", exact=True)

    # Tab content
    @property
    def profile_panel(self) -> Locator:
        return self.page.get_by_role("group", name="基本资料", exact=True)

    @property
    def notifications_panel(self) -> Locator:
        return self.page.get_by_role("heading", name="通知偏好", exact=True)

    @property
    def connected_panel(self) -> Locator:
        return self.page.get_by_role("heading", name="第三方关联", exact=True)

    @property
    def account_panel(self) -> Locator:
        return self.page.get_by_role("group", name="账户信息", exact=True)

    # 资料表单
    @property
    def nickname_input(self) -> Locator:
        return self.page.get_by_placeholder("昵称")

    @property
    def bio_textarea(self) -> Locator:
        return self.page.get_by_placeholder("介绍一下自己吧，支持 Markdown")

    @property
    def save_profile_btn(self) -> Locator:
        return self.page.get_by_role("button", name="保存")

    # 密码表单 (在 /settings/account)
    @property
    def current_password_input(self) -> Locator:
        return self.page.get_by_placeholder("当前密码")

    @property
    def new_password_input(self) -> Locator:
        return self.page.get_by_placeholder("新密码")

    @property
    def change_password_btn(self) -> Locator:
        return self.page.get_by_role("button", name="修改密码")

    # ── 操作 ──────────────────────────────────────────────────

    def go(self) -> None:
        self.navigate(self.path)

    def go_account(self) -> None:
        self.navigate("/settings/account")

    def switch_to_profile(self) -> None:
        self.profile_tab.click()

    def switch_to_notifications(self) -> None:
        self.notifications_tab.click()

    def switch_to_connected(self) -> None:
        self.connected_tab.click()

    def update_nickname(self, nickname: str) -> None:
        """更新昵称并保存"""
        self.nickname_input.fill(nickname)
        self.save_profile_btn.click()

    def change_password(
        self, current_password: str, new_password: str
    ) -> None:
        """修改密码"""
        self.current_password_input.fill(current_password)
        self.new_password_input.fill(new_password)
        self.change_password_btn.click()
