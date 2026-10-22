"""
创建帖子页 Page Object

Selenium 学习要点 — 复杂表单处理：
  1. <select> 下拉框：使用 Select 类操作
     from selenium.webdriver.support.ui import Select
     Select(element).select_by_visible_text("分区名")

  2. Markdown 编辑器：本质是 <textarea>
     用 send_keys() 输入 Markdown 文本

  3. 标签输入：输入文本后按 Enter/Space 解析为标签徽章

  4. 表单验证：发布按钮在表单无效时 disabled
"""

import time

from selenium.webdriver.remote.webdriver import WebDriver
from selenium.webdriver.remote.webelement import WebElement
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait, Select
from selenium.webdriver.support import expected_conditions as EC
from selenium.common.exceptions import TimeoutException, NoSuchElementException

from tests.e2e.selenium.pages.base_page import BasePage
from tests.e2e.selenium.config.settings import settings


class ThreadCreatePage(BasePage):
    """发帖页 — 分区选择、标题、标签、Markdown编辑器、草稿"""

    def __init__(self, driver: WebDriver):
        super().__init__(driver)
        self.path = "/threads/new"

    # ═══════════════════════════════════════════════════════════
    # 定位器
    # ═══════════════════════════════════════════════════════════

    # 表单元素
    # 源码: <h1 class="text-2xl font-semibold mb-4">发帖</h1>
    HEADING_XPATH = "//h1[contains(.,'发帖')]"
    # 源码: <select v-model="form.sectionId">...</select>
    SECTION_SELECT_CSS = "#thread-create-section"
    TITLE_INPUT_CSS = "input[placeholder*='请输入标题']"
    TAG_INPUT_CSS = "input[placeholder*='用空格或逗号分隔']"
    # MarkdownTextareaEditor 组件中包含 textarea
    CONTENT_EDITOR_CSS = "textarea[placeholder*='请输入帖子正文']"

    # 按钮
    # 源码: <button @click="submit">{{ submitting ? '发布中...' : '发布' }}</button>
    SUBMIT_BTN_XPATH = "//button[contains(normalize-space(.),'发布') and not(contains(normalize-space(.),'发布中'))]"
    SAVE_DRAFT_BTN_XPATH = "//button[contains(normalize-space(.),'保存草稿') or contains(normalize-space(.),'保存到本机')]"
    BEAUTIFY_BTN_XPATH = "//button[contains(.,'美化内容')]"
    CLEAR_DRAFT_BTN_XPATH = "//button[contains(.,'清除草稿')]"
    # 源码: <button @click="onLoginClick">立即登录</button>
    LOGIN_BTN_XPATH = "//button[contains(.,'立即登录')]"

    # 提示
    # 源码: <span>请先登录后再发帖</span>
    LOGIN_PROMPT_TEXT = "请先登录后再发帖"
    SUCCESS_CSS = "[role='status'][class*='text-emerald']"
    ERROR_CSS = "[role='alert']"

    # ── 定位器属性 ─────────────────────────────────────────

    @property
    def heading(self) -> WebElement:
        return self.driver.find_element(By.XPATH, self.HEADING_XPATH)

    @property
    def section_select(self) -> WebElement:
        return self.driver.find_element(By.CSS_SELECTOR, self.SECTION_SELECT_CSS)

    @property
    def title_input(self) -> WebElement:
        return self.driver.find_element(By.CSS_SELECTOR, self.TITLE_INPUT_CSS)

    @property
    def tag_input(self) -> WebElement:
        return self.driver.find_element(By.CSS_SELECTOR, self.TAG_INPUT_CSS)

    @property
    def content_editor(self) -> WebElement:
        return self.driver.find_element(By.CSS_SELECTOR, self.CONTENT_EDITOR_CSS)

    @property
    def submit_btn(self) -> WebElement:
        return self._visible_button(self.SUBMIT_BTN_XPATH)

    @property
    def save_draft_btn(self) -> WebElement:
        return self._visible_button(self.SAVE_DRAFT_BTN_XPATH)

    @property
    def beautify_btn(self) -> WebElement:
        return self._visible_button(self.BEAUTIFY_BTN_XPATH)

    @property
    def clear_draft_btn(self) -> WebElement:
        return self._visible_button(self.CLEAR_DRAFT_BTN_XPATH)

    @property
    def login_btn(self) -> WebElement:
        return self.driver.find_element(By.XPATH, self.LOGIN_BTN_XPATH)

    def _visible_button(self, xpath: str) -> WebElement:
        for element in self.driver.find_elements(By.XPATH, xpath):
            if element.is_displayed():
                return element
        raise NoSuchElementException(f"未找到可见按钮: {xpath}")

    def _visible_element_by_css(self, selector: str) -> WebElement:
        for element in self.driver.find_elements(By.CSS_SELECTOR, selector):
            if element.is_displayed():
                return element
        raise NoSuchElementException(f"未找到可见元素: {selector}")

    @property
    def login_prompt(self) -> WebElement:
        return self.driver.find_element(
            By.XPATH, f"//*[contains(text(),'{self.LOGIN_PROMPT_TEXT}')]"
        )

    @property
    def success_msg(self) -> WebElement:
        return self._visible_element_by_css(self.SUCCESS_CSS)

    @property
    def error_msg(self) -> WebElement:
        return self._visible_element_by_css(self.ERROR_CSS)

    # ═══════════════════════════════════════════════════════════
    # 操作
    # ═══════════════════════════════════════════════════════════

    def go(self) -> None:
        self.navigate(self.path)

    def wait_for_form(self) -> None:
        """
        等待表单渲染完成

        认证页面有两种可能：
          1. 已登录 → 显示发帖表单
          2. 未登录 → 显示"请先登录"提示
        """
        WebDriverWait(self.driver, 10).until(
            lambda d: (
                # 已登录：表单出现
                len(d.find_elements(By.XPATH, self.HEADING_XPATH)) > 0
                # 未登录：提示出现
                or self.LOGIN_PROMPT_TEXT in d.find_element(By.TAG_NAME, "body").text
            )
        )

    def select_section(self, name: str = None) -> None:
        """
        选择分区

        Selenium 学习要点 — Select 类：
          from selenium.webdriver.support.ui import Select

          select = Select(element)
          select.select_by_visible_text("分区名")   # 按显示文本
          select.select_by_value("1")               # 按 value 属性
          select.select_by_index(1)                 # 按索引（跳过第一个 disabled option）
          select.first_selected_option              # 获取当前选中项

        Args:
            name: 分区名称。None 则选第一个有效选项。
        """
        select = Select(self.section_select)
        if name:
            select.select_by_visible_text(name)
        else:
            # 第一个 option 通常是 "请选择分区" (disabled)
            # 选第二个
            options = select.options
            if len(options) > 1:
                select.select_by_index(1)

    def fill_title(self, title: str) -> None:
        """填写标题"""
        self.title_input.clear()
        self.title_input.send_keys(title)

    def fill_tags(self, tags: str) -> None:
        """
        填写标签

        标签用空格或逗号分隔，输入后自动解析为标签徽章
        """
        self.tag_input.clear()
        self.tag_input.send_keys(tags)

    def fill_content(self, content: str) -> None:
        """
        填写 Markdown 内容

        Selenium 学习要点：
          textarea 用 send_keys() 输入
          如果要用 Markdown 语法，直接输入 Markdown 文本即可
        """
        self.content_editor.clear()
        self.content_editor.send_keys(content)

    def fill_form(
        self,
        title: str = "测试帖子",
        content: str = "测试内容",
        tags: str = "测试标签",
    ) -> None:
        """快捷填写完整表单"""
        self.select_section()
        self.fill_title(title)
        self.fill_tags(tags)
        self.fill_content(content)

    def submit(self) -> None:
        """
        提交帖子

        Selenium 学习要点 — 按钮状态：
          发布按钮在表单验证通过前是 disabled 的
          需要等待按钮 enabled 再点击
        """
        # 移动端与桌面端各有一个按钮；选择当前可见按钮，空表单保持 disabled。
        button = WebDriverWait(self.driver, 10).until(
            lambda d: next(
                (element for element in d.find_elements(By.XPATH, self.SUBMIT_BTN_XPATH)
                 if element.is_displayed()),
                False,
            )
        )
        if button.is_enabled():
            button.click()

    def save_draft(self) -> None:
        """保存草稿"""
        self.save_draft_btn.click()
        time.sleep(0.5)

    def beautify(self) -> None:
        """AI 美化内容"""
        self.beautify_btn.click()
        time.sleep(0.5)

    def clear_draft(self) -> None:
        """清除草稿"""
        self.clear_draft_btn.click()
        time.sleep(0.5)

    def click_login(self) -> None:
        """点击"立即登录"按钮"""
        self.login_btn.click()
        time.sleep(0.3)

    # ═══════════════════════════════════════════════════════════
    # 状态检查
    # ═══════════════════════════════════════════════════════════

    def is_login_prompt_shown(self) -> bool:
        try:
            return self.login_prompt.is_displayed()
        except NoSuchElementException:
            return False

    def is_success_shown(self) -> bool:
        try:
            return self.success_msg.is_displayed()
        except NoSuchElementException:
            return False

    def is_error_shown(self) -> bool:
        try:
            return self.error_msg.is_displayed()
        except NoSuchElementException:
            return False

    def is_submit_disabled(self) -> bool:
        """检查发布按钮是否 disabled"""
        return not self.submit_btn.is_enabled()
