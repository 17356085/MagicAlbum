"""
Vue.js SPA 异步等待辅助工具（Selenium 版本）

Selenium 学习要点 — WebDriverWait + ExpectedConditions：
  这是 Selenium 最重要的概念之一！

  1. 为什么需要显式等待？
     Vue SPA 是异步渲染的：点击按钮 → API 请求 → 数据返回 → DOM 更新
     Selenium 不会自动等这些完成，必须手动等待条件满足。

  2. WebDriverWait(driver, timeout).until(condition)
     - 每隔 0.5 秒检查一次 condition
     - 条件满足立即返回，超时抛出 TimeoutException
     - 是最可靠的等待方式

  3. ExpectedConditions（EC）常用条件：
     - presence_of_element_located:  元素在DOM中（不一定可见）
     - visibility_of_element_located: 元素在DOM中且可见
     - element_to_be_clickable:      元素可见且可点击
     - invisibility_of_element_located: 元素不可见
     - text_to_be_present_in_element:   元素包含指定文本

  4. Vue 特殊场景：
     - v-if:    元素完全从DOM移除/插入，需用 presence_of_element_located
     - v-show:  元素始终在DOM，仅切换 display，需用 visibility_of_element_located
     - v-for:   列表渲染可能分批，等待第一项出现即可
     - 防抖:    输入后 250ms 才触发请求，需额外 time.sleep(0.3)
"""

import re
import time
from typing import Optional

from selenium.webdriver.remote.webdriver import WebDriver
from selenium.webdriver.remote.webelement import WebElement
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.common.by import By
from selenium.common.exceptions import TimeoutException, StaleElementReferenceException

from tests.e2e.selenium.config.settings import settings


# ── 通用等待条件 ────────────────────────────────────────────────────

def wait_for_element(
    driver: WebDriver,
    by: By,
    value: str,
    timeout: float = None,
    condition: str = "visible"
) -> WebElement:
    """
    等待元素满足条件后返回

    Args:
        driver: WebDriver 实例
        by: 定位方式 (By.ID, By.CSS_SELECTOR, By.XPATH 等)
        value: 定位值
        timeout: 超时秒数，默认使用配置值
        condition: 条件类型
            - "present":  元素在DOM中即可
            - "visible":  元素可见
            - "clickable": 元素可点击

    Returns:
        找到的 WebElement

    Raises:
        TimeoutException: 超时未找到

    Selenium 学习要点：
      By 是定位策略枚举，常用：
        By.ID              → id="xxx"
        By.CLASS_NAME      → class="xxx"（单个类名）
        By.CSS_SELECTOR    → CSS 选择器（最灵活）
        By.XPATH           → XPath 表达式（最强大但最慢）
        By.NAME            → name="xxx"
        By.TAG_NAME        → 标签名如 div, input
        By.LINK_TEXT       → 链接的完整文字
        By.PARTIAL_LINK_TEXT → 链接的部分文字
    """
    wait = WebDriverWait(driver, timeout or settings.EXPLICIT_WAIT)

    condition_map = {
        "present": EC.presence_of_element_located((by, value)),
        "visible": EC.visibility_of_element_located((by, value)),
        "clickable": EC.element_to_be_clickable((by, value)),
    }

    return wait.until(condition_map[condition])


def wait_for_element_to_disappear(
    driver: WebDriver,
    by: By,
    value: str,
    timeout: float = None
) -> bool:
    """
    等待元素从DOM中消失（v-if 切换为 false）

    用于：等待"加载中..."消失

    Returns:
        True 如果元素消失，False 如果超时
    """
    try:
        WebDriverWait(driver, timeout or settings.EXPLICIT_WAIT).until(
            EC.invisibility_of_element_located((by, value))
        )
        return True
    except TimeoutException:
        return False


# ── Vue SPA 专用等待 ────────────────────────────────────────────────

def wait_for_loading_gone(driver: WebDriver, timeout: float = 15) -> None:
    """
    等待 Vue 加载状态消失

    Vue 组件通常用 v-if 切换 loading 状态：
      <div v-if="loading">正在加载...</div>
      <div v-else>实际内容</div>

    这个函数会轮询所有常见的加载文案
    """
    loading_texts = ["正在加载...", "加载中...", "Loading..."]
    start = time.time()
    deadline = start + timeout

    while time.time() < deadline:
        body_text = driver.find_element(By.TAG_NAME, "body").text
        # 检查是否还有加载文案
        if not any(t in body_text for t in loading_texts):
            return  # 加载完成
        time.sleep(0.3)

    # 超时不抛异常，可能页面本身就没有 loading 状态


def wait_for_vue_route(
    driver: WebDriver,
    url_pattern: str,
    timeout: float = 10
) -> None:
    """
    等待 Vue Router 导航到目标 URL

    Selenium 没有 Playwright 的 wait_for_url，
    用轮询方式检查当前 URL

    Args:
        driver: WebDriver
       url_pattern: URL 正则或子串，如 r'/threads/\\d+'
    """
    wait = WebDriverWait(driver, timeout)
    try:
        wait.until(lambda d: re.search(url_pattern, d.current_url) is not None)
    except TimeoutException:
        # 打印当前 URL 辅助调试
        current = driver.current_url
        raise TimeoutException(
            f"等待 URL 匹配 '{url_pattern}' 超时，当前 URL: {current}"
        )


def wait_for_text_in_body(
    driver: WebDriver,
    text: str,
    timeout: float = 10
) -> bool:
    """
    等待指定文本出现在页面上

    用于：等待 Toast 消息、错误提示等动态文本
    """
    try:
        WebDriverWait(driver, timeout).until(
            lambda d: text in d.find_element(By.TAG_NAME, "body").text
        )
        return True
    except TimeoutException:
        return False


def wait_for_v_else_gone(
    driver: WebDriver,
    css_selector: str,
    timeout: float = 5
) -> None:
    """
    等待 v-if/v-else 中的旧状态元素消失

    例如：等待 v-if="loading" 的加载指示器消失后，
    v-else 的内容才会渲染

    Args:
        css_selector: 旧状态元素的CSS选择器
    """
    try:
        wait_for_element_to_disappear(
            driver, By.CSS_SELECTOR, css_selector, timeout
        )
    except TimeoutException:
        pass  # 元素可能本来就不存在


def wait_for_vue_list_loaded(
    driver: WebDriver,
    list_selector: str,
    min_items: int = 1,
    timeout: float = 10
) -> list[WebElement]:
    """
    等待 v-for 渲染的列表出现足够数量的元素

    Args:
        list_selector: 列表项的 CSS 选择器
        min_items: 最少期望元素数

    Returns:
        列表项的 WebElement 列表
    """
    wait = WebDriverWait(driver, timeout)
    try:
        wait.until(
            lambda d: len(d.find_elements(By.CSS_SELECTOR, list_selector)) >= min_items
        )
    except TimeoutException:
        pass  # 可能列表为空（合法情况）

    return driver.find_elements(By.CSS_SELECTOR, list_selector)


# ── 安全操作 ────────────────────────────────────────────────────────

def safe_click(
    driver: WebDriver,
    element: WebElement,
    retries: int = 3
) -> None:
    """
    安全点击 — 自动重试处理 StaleElementReferenceException

    Selenium 学习要点：
      StaleElementReferenceException 是 Selenium 中最常见的异常之一。
      当 DOM 更新后，之前获取的 WebElement 引用就"过时"了。

      解决办法：
        1. 重新获取元素再操作（本函数的方式）
        2. 使用显式等待获取新鲜引用

    Args:
        driver: WebDriver
        element: 要点击的元素
        retries: 重试次数
    """
    for attempt in range(retries):
        try:
            # 先等待元素可点击
            WebDriverWait(driver, 5).until(
                EC.element_to_be_clickable((By.XPATH, _get_xpath(element)))
            )
            element.click()
            return
        except StaleElementReferenceException:
            if attempt == retries - 1:
                raise
            time.sleep(0.5)
            # 重新查找元素（简化处理：用XPATH重新定位）
            element = driver.find_element(By.XPATH, _get_xpath(element))


def safe_send_keys(
    driver: WebDriver,
    element: WebElement,
    text: str,
    clear_first: bool = True
) -> None:
    """
    安全输入 — 先等待可点击，再清空+输入

    Args:
        driver: WebDriver
        element: 输入框元素
        text: 要输入的文本
        clear_first: 是否先清空现有内容
    """
    WebDriverWait(driver, 5).until(
        EC.element_to_be_clickable((By.XPATH, _get_xpath(element)))
    )
    if clear_first:
        element.clear()
    element.send_keys(text)


def _get_xpath(element: WebElement) -> str:
    """
    （内部）从 WebElement 提取一个可用的 XPath 定位器
    用于在 StaleElement 后重新定位
    """
    # 尝试从元素获取 tag 和 id
    tag = element.tag_name
    el_id = element.get_attribute("id")
    if el_id:
        return f"//{tag}[@id='{el_id}']"

    el_class = element.get_attribute("class")
    if el_class:
        first_class = el_class.split()[0]
        return f"//{tag}[contains(@class,'{first_class}')]"

    # 回退：用 text
    text = element.text[:50] if element.text else ""
    if text:
        return f"//{tag}[contains(text(),'{text}')]"

    return f"//{tag}"
