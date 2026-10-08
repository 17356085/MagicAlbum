"""
顶部导航栏 Page Object

Selenium 学习要点 — aria-label 定位：
  实际 DOM: <button aria-label="登录"><svg>...</svg><span class="sr-only">登录</span></button>
  XPath contains(text(),'登录') 无法匹配——"登录"在子元素 span 里，不是 button 的直接文本节点。
  正确做法：用 aria-label 属性定位。
"""

from selenium.webdriver.remote.webdriver import WebDriver
from selenium.webdriver.remote.webelement import WebElement
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.common.exceptions import NoSuchElementException

from tests.e2e.selenium.pages.base_page import BasePage


class HeaderPage(BasePage):
    """顶部导航栏"""

    def __init__(self, driver: WebDriver):
        super().__init__(driver)

    # ═══════════════════════════════════════════════════════════
    # 定位器
    # ═══════════════════════════════════════════════════════════

    # 品牌/Logo
    BRAND_LINK_XPATH = "//a[contains(@href,'/discover')]"

    # 搜索输入框
    SEARCH_INPUT_CSS = "input[placeholder*='搜索']"

    # 登录按钮（未登录时在Header显示）
    # 源码: <button aria-label="登录" title="登录">  (AuthEntry.vue)
    LOGIN_BUTTON_CSS = "button[aria-label='登录']"

    @property
    def brand_link(self) -> WebElement:
        return self.driver.find_element(By.XPATH, self.BRAND_LINK_XPATH)

    @property
    def search_input(self) -> WebElement:
        return self.driver.find_element(By.CSS_SELECTOR, self.SEARCH_INPUT_CSS)

    @property
    def login_button(self) -> WebElement:
        """Header 中的登录入口按钮"""
        return self.driver.find_element(By.CSS_SELECTOR, self.LOGIN_BUTTON_CSS)

    # ═══════════════════════════════════════════════════════════
    # 操作
    # ═══════════════════════════════════════════════════════════

    def click_brand(self) -> None:
        self.brand_link.click()
        self.wait_for_page_ready()

    def click_login(self) -> None:
        """点击 Header 登录按钮，打开 AuthModal"""
        WebDriverWait(self.driver, 5).until(
            EC.element_to_be_clickable((By.CSS_SELECTOR, self.LOGIN_BUTTON_CSS))
        ).click()

    def is_logged_in(self) -> bool:
        try:
            self.driver.find_element(By.CSS_SELECTOR, self.LOGIN_BUTTON_CSS)
            return False  # 登录按钮还在 → 未登录
        except NoSuchElementException:
            return True   # 登录按钮消失 → 已登录
