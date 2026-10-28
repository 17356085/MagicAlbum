"""
用户主页 Page Object

路由: /users/:id
功能: 用户资料展示、帖子列表、关注/取关
"""

from playwright.sync_api import Locator, Page

from tests.e2e.playwright.pages.base_page import BasePage
from tests.e2e.playwright.utils.wait_helpers import wait_for_loading_gone


class UserProfilePage(BasePage):
    """用户主页"""

    def __init__(self, page: Page, base_url: str | None = None):
        super().__init__(page, base_url)

    # ── 定位器 ────────────────────────────────────────────────

    @property
    def username_display(self) -> Locator:
        """The username line rendered below the profile display name."""
        return self.page.locator(
            "section.ui-page > section.ui-panel header p.ui-description"
        )

    @property
    def nickname_display(self) -> Locator:
        """The profile display name rendered in the page heading."""
        return self.page.locator(
            "section.ui-page > section.ui-panel header h1.ui-title"
        )

    @property
    def error_alert(self) -> Locator:
        """The profile-level API error region."""
        return self.page.locator("section.ui-page > div[role='alert']")

    @property
    def avatar(self) -> Locator:
        return self.page.locator("img.rounded-full").first

    @property
    def follow_btn(self) -> Locator:
        """关注/取关按钮"""
        return self.page.get_by_role("button", name="关注", exact=True)

    @property
    def unfollow_btn(self) -> Locator:
        return self.page.get_by_role("button", name="已关注", exact=True)

    @property
    def following_count(self) -> Locator:
        """关注数链接"""
        return self.page.locator("a[href*='/following']").first

    @property
    def followers_count(self) -> Locator:
        """粉丝数链接"""
        return self.page.locator("a[href*='/followers']").first

    @property
    def user_threads(self) -> Locator:
        """用户帖子列表"""
        return self.page.locator("ul.space-y-4 > li").first

    @property
    def loading_indicator(self) -> Locator:
        return self.page.get_by_text("正在加载...")

    # ── 操作 ──────────────────────────────────────────────────

    def go(self, user_id: int) -> None:
        self.navigate(f"/users/{user_id}")

    def wait_for_content(self) -> None:
        wait_for_loading_gone(self.page)

    def click_follow(self) -> None:
        self.follow_btn.click()
        self.page.wait_for_timeout(500)

    def click_unfollow(self) -> None:
        self.unfollow_btn.click()
        self.page.wait_for_timeout(500)

    def go_to_following(self) -> None:
        self.following_count.click()
        self.page.wait_for_url("**/following", timeout=5000)

    def go_to_followers(self) -> None:
        self.followers_count.click()
        self.page.wait_for_url("**/followers", timeout=5000)

    # ── 状态 ──────────────────────────────────────────────────

    def is_following(self) -> bool:
        return self.unfollow_btn.is_visible()

    def is_not_following(self) -> bool:
        return self.follow_btn.is_visible()
