"""Shared fixtures for the canonical API, Selenium, and Playwright suites."""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

import httpx
import pytest
from dotenv import load_dotenv
from playwright.sync_api import Browser, BrowserContext, Page, Playwright, sync_playwright
from selenium.webdriver.remote.webdriver import WebDriver

from tests.api.api_client import ApiClient


TEST_ROOT = Path(__file__).resolve().parent
ENV_FILE = TEST_ROOT / ".env"
if ENV_FILE.exists():
    load_dotenv(ENV_FILE)


BASE_URL = os.getenv("TEST_BASE_URL", "http://localhost:5173").rstrip("/")
API_BASE_URL = os.getenv("TEST_API_BASE_URL", "http://localhost:9000/api/v1").rstrip("/")
TEST_PHONE = os.getenv("TEST_PHONE", "")
TEST_EMAIL = os.getenv("TEST_EMAIL", "")
TEST_PASSWORD = os.getenv("TEST_PASSWORD", "")
BROWSER_HEADLESS = os.getenv("TEST_HEADLESS", "true").lower() == "true"
BROWSER_NAME = os.getenv("TEST_BROWSER", "chrome").lower()
BROWSER_SLOW_MO = int(os.getenv("TEST_SLOW_MO", "0"))
PLAYWRIGHT_CHANNEL = os.getenv("TEST_PLAYWRIGHT_CHANNEL", "").strip() or None
VIEWPORT_WIDTH = int(os.getenv("TEST_VIEWPORT_WIDTH", "1280"))
VIEWPORT_HEIGHT = int(os.getenv("TEST_VIEWPORT_HEIGHT", "900"))


def _extract_payload(data: dict[str, Any]) -> dict[str, Any]:
    payload = data.get("data", data)
    return payload if isinstance(payload, dict) else {}


def _extract_token(data: dict[str, Any]) -> str:
    payload = _extract_payload(data)
    return (
        payload.get("accessToken")
        or payload.get("access_token")
        or payload.get("token")
        or payload.get("jwt")
        or ""
    )


def _extract_user(data: dict[str, Any]) -> dict[str, Any] | None:
    payload = _extract_payload(data)
    user = payload.get("user") or payload.get("profile")
    return user if isinstance(user, dict) else None


def _login(client: ApiClient) -> httpx.Response:
    if TEST_EMAIL:
        return client.login_email_password(TEST_EMAIL, TEST_PASSWORD)
    return client.login_phone_password(TEST_PHONE, TEST_PASSWORD)


def _configure_selenium_settings() -> None:
    from tests.e2e.selenium.config.settings import settings

    settings.BASE_URL = BASE_URL
    settings.API_BASE_URL = API_BASE_URL
    settings.BROWSER = BROWSER_NAME
    settings.HEADLESS = BROWSER_HEADLESS
    settings.WINDOW_WIDTH = VIEWPORT_WIDTH
    settings.WINDOW_HEIGHT = VIEWPORT_HEIGHT
    settings.TEST_PHONE = TEST_PHONE
    settings.TEST_EMAIL = TEST_EMAIL
    settings.TEST_PASSWORD = TEST_PASSWORD


def pytest_addoption(parser: pytest.Parser) -> None:
    parser.addoption(
        "--api-url",
        action="store",
        default=API_BASE_URL,
        help="API base URL (overrides TEST_API_BASE_URL)",
    )
    parser.addoption(
        "--selenium-browser",
        action="store",
        choices=["chrome", "firefox", "edge"],
        default=BROWSER_NAME,
        help="Selenium browser selected by Selenium Manager",
    )
    parser.addoption(
        "--selenium-headed",
        action="store_true",
        default=False,
        help="Run Selenium and Playwright with visible browser windows",
    )


def pytest_configure(config: pytest.Config) -> None:
    global API_BASE_URL, BROWSER_HEADLESS, BROWSER_NAME

    API_BASE_URL = config.getoption("--api-url")
    BROWSER_HEADLESS = not config.getoption("--selenium-headed")
    BROWSER_NAME = config.getoption("--selenium-browser")
    _configure_selenium_settings()


@pytest.fixture(scope="session")
def driver() -> WebDriver:
    """One Selenium WebDriver per session; Selenium Manager resolves its driver."""
    from tests.e2e.selenium.utils.driver_factory import DriverFactory

    driver_instance = DriverFactory.create_driver()
    yield driver_instance
    driver_instance.quit()


@pytest.fixture
def clean_page(driver: WebDriver) -> WebDriver:
    """Clear browser storage before each Selenium test."""
    from tests.e2e.selenium.config.settings import settings

    driver.get(settings.BASE_URL)
    driver.execute_script("localStorage.clear(); sessionStorage.clear();")
    yield driver


@pytest.fixture(scope="session")
def auth_session() -> dict[str, Any]:
    """Authenticate once and share the resulting JWT/user across auth fixtures."""
    if not (TEST_EMAIL or TEST_PHONE) or not TEST_PASSWORD:
        pytest.fail("认证测试需要 TEST_EMAIL 或 TEST_PHONE，以及 TEST_PASSWORD")

    client = ApiClient(API_BASE_URL)
    try:
        try:
            response = _login(client)
        except Exception as exc:
            pytest.fail(f"认证登录请求失败: {type(exc).__name__}")

        if response.status_code != 200:
            pytest.fail(f"认证登录失败，HTTP {response.status_code}")

        try:
            response_data = response.json() if response.content else {}
        except Exception as exc:
            pytest.fail(f"认证登录响应解析失败: {type(exc).__name__}")

        token = _extract_token(response_data)
        user = _extract_user(response_data)
        if not token:
            pytest.fail("认证登录成功但未返回 JWT")
        if not user:
            pytest.fail("认证登录成功但未返回用户信息")
        if not (user.get("id") or user.get("userId")):
            pytest.fail("认证登录成功但用户信息缺少 ID")
        return {"access_token": token, "user": user}
    finally:
        client.close()


@pytest.fixture
def auth_page(driver: WebDriver, auth_session: dict[str, Any]) -> WebDriver:
    """Inject the session-authenticated token/user into Selenium."""
    from tests.e2e.selenium.config.settings import settings

    driver.get(settings.BASE_URL)
    driver.execute_script(
        "localStorage.setItem('accessToken', arguments[0]);",
        auth_session["access_token"],
    )
    driver.execute_script(
        "localStorage.setItem('currentUser', JSON.stringify(arguments[0]));",
        auth_session["user"],
    )
    driver.get(f"{settings.BASE_URL}/discover")
    return driver


@pytest.fixture
def api_client() -> ApiClient:
    client = ApiClient(API_BASE_URL)
    yield client
    client.close()


@pytest.fixture
def auth_api_client(auth_session: dict[str, Any]) -> ApiClient:
    """Create a client using the session-authenticated token."""
    client = ApiClient(API_BASE_URL)
    client.set_token(auth_session["access_token"])
    yield client
    client.close()


@pytest.fixture(scope="session")
def playwright_instance() -> Playwright:
    with sync_playwright() as playwright:
        yield playwright


@pytest.fixture(scope="session")
def pw_browser(playwright_instance: Playwright) -> Browser:
    browser = playwright_instance.chromium.launch(
        channel=PLAYWRIGHT_CHANNEL,
        headless=BROWSER_HEADLESS,
        slow_mo=BROWSER_SLOW_MO,
        args=["--no-sandbox", "--disable-setuid-sandbox"],
    )
    yield browser
    browser.close()


@pytest.fixture
def context(pw_browser: Browser) -> BrowserContext:
    browser_context = pw_browser.new_context(
        viewport={"width": VIEWPORT_WIDTH, "height": VIEWPORT_HEIGHT},
        locale="zh-CN",
        timezone_id="Asia/Shanghai",
        color_scheme="light",
    )
    yield browser_context
    browser_context.close()


@pytest.fixture
def page(context: BrowserContext) -> Page:
    page_obj = context.new_page()
    yield page_obj
    page_obj.close()


@pytest.fixture
def authenticated_page(page: Page, auth_session: dict[str, Any]) -> Page:
    """Inject the session-authenticated token/user into Playwright."""
    page.goto(BASE_URL, wait_until="domcontentloaded")
    page.evaluate(
        "(value) => localStorage.setItem('accessToken', value)",
        auth_session["access_token"],
    )
    page.evaluate(
        "(value) => localStorage.setItem('currentUser', JSON.stringify(value))",
        auth_session["user"],
    )
    page.goto(f"{BASE_URL}/discover", wait_until="domcontentloaded")
    return page
