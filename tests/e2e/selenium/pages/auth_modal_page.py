"""
登录弹窗 Page Object

实际 DOM 结构（从 AuthModal.vue / AuthLoginForm.vue / AuthRegisterForm.vue）：

  AuthModal (v-if="showLogin"):
    div.fixed.inset-0.z-50.flex.items-center.justify-center   ← 最外层
      div.absolute.inset-0.bg-black/30 (@click="onClose")     ← 点击遮罩关闭
      div.relative.z-10.w-full.max-w-[700px]...               ← 弹窗主体
        div.grid.md:grid-cols-[236px_minmax(0,1fr)]
          section (左侧: QR码扫码登录)
          section.flex.min-h-[360px]... (右侧: 表单区)
            div (header: 登录/注册 切换 + 关闭按钮)
              button.rounded-full  "登录"     ← 登录Tab
              button.rounded-full  "注册"     ← 注册Tab
              button[aria-label="关闭"]  ✕     ← 关闭按钮
            div.flex-1
              AuthLoginForm (v-if="authView === 'login'")
                form
                  input[type='tel'] placeholder="请输入手机号"
                  input[type='password'] placeholder="请输入密码"
                  button[type='submit']  "登录" / "登录中…"
              AuthRegisterForm (v-else)
                form
                  input[type='text'] placeholder="请输入用户名"
                  input[type='tel'] placeholder="请输入手机号"
                  input[type='email'] placeholder="name@example.com"
                  input[type='password'] (密码)
                  input[type='password'] (确认密码)
                  button[type='submit']  "注册" / "提交中…"
"""

from selenium.webdriver.remote.webdriver import WebDriver
from selenium.webdriver.remote.webelement import WebElement
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.common.exceptions import NoSuchElementException, TimeoutException

from tests.e2e.selenium.pages.base_page import BasePage
from tests.e2e.selenium.config.settings import settings


class AuthModalPage(BasePage):
    """登录弹窗"""

    def __init__(self, driver: WebDriver):
        super().__init__(driver)

    # ═══════════════════════════════════════════════════════════
    # 定位器
    # ═══════════════════════════════════════════════════════════

    # 弹窗最外层容器
    MODAL_ROOT_CSS = "div.ui-dialog-layer"

    # 关闭按钮
    CLOSE_BTN_CSS = "button[aria-label='关闭']"

    # 登录/注册 Tab 切换（在弹窗 header 中，有 rounded-full 类）
    LOGIN_TAB_XPATH = "//div[contains(@class,'ui-dialog-layer')]//button[contains(@class,'auth-view-tab') and normalize-space(.)='登录']"
    REGISTER_TAB_XPATH = "//div[contains(@class,'ui-dialog-layer')]//button[contains(@class,'auth-view-tab') and normalize-space(.)='注册']"

    # 登录表单（AuthLoginForm）
    LOGIN_PHONE_CSS = "div.ui-dialog-layer #auth-login-phone, div.ui-dialog-layer #auth-login-phone-code"
    LOGIN_EMAIL_CSS = "div.ui-dialog-layer #auth-login-email"
    LOGIN_PASSWORD_CSS = "div.ui-dialog-layer #auth-login-password"
    LOGIN_CODE_CSS = "div.ui-dialog-layer #auth-login-code"
    LOGIN_SUBMIT_XPATH = "//div[contains(@class,'ui-dialog-layer')]//form//button[@type='submit' and normalize-space(.)='登录']"

    # 注册表单（AuthRegisterForm）
    REGISTER_USERNAME_CSS = "div.ui-dialog-layer #auth-register-username"
    REGISTER_PHONE_CSS = "div.ui-dialog-layer #auth-register-phone"
    REGISTER_EMAIL_CSS = "div.ui-dialog-layer #auth-register-email"
    REGISTER_PASSWORD_CSS = "div.ui-dialog-layer #auth-register-password"
    REGISTER_CONFIRM_CSS = "div.ui-dialog-layer #auth-register-confirm-password"
    REGISTER_SUBMIT_XPATH = "//div[contains(@class,'ui-dialog-layer')]//form//button[@type='submit' and normalize-space(.)='注册']"

    # ── 定位器属性 ─────────────────────────────────────────

    @property
    def modal_root(self) -> WebElement:
        return self.driver.find_element(By.CSS_SELECTOR, self.MODAL_ROOT_CSS)

    @property
    def close_button(self) -> WebElement:
        return self.driver.find_element(By.CSS_SELECTOR, self.CLOSE_BTN_CSS)

    @property
    def login_tab(self) -> WebElement:
        return self.driver.find_element(By.XPATH, self.LOGIN_TAB_XPATH)

    @property
    def register_tab(self) -> WebElement:
        return self.driver.find_element(By.XPATH, self.REGISTER_TAB_XPATH)

    @property
    def phone_input(self) -> WebElement:
        return self.driver.find_element(By.CSS_SELECTOR, self.LOGIN_PHONE_CSS)

    @property
    def email_input(self) -> WebElement:
        return self.driver.find_element(By.CSS_SELECTOR, self.LOGIN_EMAIL_CSS)

    @property
    def password_input(self) -> WebElement:
        return self.driver.find_element(By.CSS_SELECTOR, self.LOGIN_PASSWORD_CSS)

    @property
    def login_submit_btn(self) -> WebElement:
        return self.driver.find_element(By.XPATH, self.LOGIN_SUBMIT_XPATH)

    @property
    def register_username_input(self) -> WebElement:
        return self.driver.find_element(By.CSS_SELECTOR, self.REGISTER_USERNAME_CSS)

    @property
    def register_submit_btn(self) -> WebElement:
        return self.driver.find_element(By.XPATH, self.REGISTER_SUBMIT_XPATH)

    # ═══════════════════════════════════════════════════════════
    # 操作
    # ═══════════════════════════════════════════════════════════

    def wait_for_modal(self, timeout: float = None) -> None:
        """等待登录弹窗出现并完成入场动画"""
        wait = WebDriverWait(self.driver, timeout or settings.EXPLICIT_WAIT)
        wait.until(
            EC.visibility_of_element_located(
                (By.CSS_SELECTOR, self.MODAL_ROOT_CSS)
            )
        )

    def wait_for_modal_closed(self, timeout: float = 5) -> bool:
        """等待弹窗关闭"""
        try:
            WebDriverWait(self.driver, timeout).until(
                EC.invisibility_of_element_located(
                    (By.CSS_SELECTOR, self.MODAL_ROOT_CSS)
                )
            )
            return True
        except TimeoutException:
            return False

    def close(self) -> None:
        self.close_button.click()

    def switch_to_login(self) -> None:
        self.login_tab.click()
        WebDriverWait(self.driver, settings.EXPLICIT_WAIT).until(
            EC.visibility_of_element_located((By.CSS_SELECTOR, self.LOGIN_PHONE_CSS))
        )

    def switch_to_register(self) -> None:
        self.register_tab.click()
        WebDriverWait(self.driver, settings.EXPLICIT_WAIT).until(
            EC.visibility_of_element_located((By.CSS_SELECTOR, self.REGISTER_USERNAME_CSS))
        )

    def login_with_phone(self, phone: str, password: str) -> None:
        """手机号密码登录"""
        self.phone_input.clear()
        self.phone_input.send_keys(phone)
        self.password_input.clear()
        self.password_input.send_keys(password)
        self.login_submit_btn.click()

    def login_with_email(self, email: str, password: str) -> None:
        """邮箱密码登录 — 需要先切换到邮箱模式"""
        # 默认是手机号密码模式，点击"切换到邮箱登录"
        switch_buttons = self.driver.find_elements(
            By.XPATH, "//div[contains(@class,'ui-dialog-layer')]//button[normalize-space(.)='切换到邮箱登录']"
        )
        if switch_buttons:
            switch_btn = switch_buttons[0]
            switch_btn.click()
            WebDriverWait(self.driver, settings.EXPLICIT_WAIT).until(
                EC.visibility_of_element_located((By.CSS_SELECTOR, self.LOGIN_EMAIL_CSS))
            )
        self.email_input.clear()
        self.email_input.send_keys(email)
        self.password_input.clear()
        self.password_input.send_keys(password)
        self.login_submit_btn.click()

    def register(self, username: str, email: str = "", phone: str = "", password: str = "") -> None:
        """注册新用户"""
        self.switch_to_register()
        self.register_username_input.clear()
        self.register_username_input.send_keys(username)

        if email:
            el = self.driver.find_element(By.CSS_SELECTOR, self.REGISTER_EMAIL_CSS)
            el.clear()
            el.send_keys(email)
        if phone:
            el = self.driver.find_element(By.CSS_SELECTOR, self.REGISTER_PHONE_CSS)
            el.clear()
            el.send_keys(phone)
        if password:
            el = self.driver.find_element(By.CSS_SELECTOR, self.REGISTER_PASSWORD_CSS)
            el.clear()
            el.send_keys(password)
            # 确认密码填一样的
            confirm_elements = self.driver.find_elements(
                By.CSS_SELECTOR, self.REGISTER_CONFIRM_CSS
            )
            if confirm_elements:
                confirm = confirm_elements[0]
                confirm.clear()
                confirm.send_keys(password)

        self.register_submit_btn.click()

    # ═══════════════════════════════════════════════════════════
    # 状态查询
    # ═══════════════════════════════════════════════════════════

    def is_visible(self) -> bool:
        try:
            return self.modal_root.is_displayed()
        except NoSuchElementException:
            return False

    def is_login_view(self) -> bool:
        return any(
            element.is_displayed()
            for element in self.driver.find_elements(By.CSS_SELECTOR, self.LOGIN_PHONE_CSS)
        ) or any(
            element.is_displayed()
            for element in self.driver.find_elements(By.CSS_SELECTOR, self.LOGIN_EMAIL_CSS)
        )

    def is_register_view(self) -> bool:
        return any(
            element.is_displayed()
            for element in self.driver.find_elements(By.CSS_SELECTOR, self.REGISTER_USERNAME_CSS)
        )
