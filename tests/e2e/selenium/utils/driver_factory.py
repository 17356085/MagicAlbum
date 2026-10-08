"""Create Selenium WebDrivers through Selenium Manager."""

from __future__ import annotations

from selenium import webdriver
from selenium.webdriver.chrome.options import Options as ChromeOptions
from selenium.webdriver.edge.options import Options as EdgeOptions
from selenium.webdriver.firefox.options import Options as FirefoxOptions

from tests.e2e.selenium.config.settings import settings


class DriverFactory:
    """Build configured browsers without checking in or downloading a driver binary."""

    @staticmethod
    def create_driver(browser_name: str | None = None) -> webdriver.Remote:
        name = (browser_name or settings.BROWSER).lower()
        factories = {
            "chrome": DriverFactory._create_chrome,
            "firefox": DriverFactory._create_firefox,
            "edge": DriverFactory._create_edge,
        }
        if name not in factories:
            raise ValueError(f"不支持的浏览器: {name}，可选: {list(factories)}")

        driver = factories[name]()
        driver.implicitly_wait(settings.IMPLICIT_WAIT)
        driver.set_page_load_timeout(settings.PAGE_LOAD_TIMEOUT)
        driver.set_window_size(settings.WINDOW_WIDTH, settings.WINDOW_HEIGHT)
        return driver

    @staticmethod
    def _create_chrome() -> webdriver.Chrome:
        options = ChromeOptions()
        if settings.HEADLESS:
            options.add_argument("--headless=new")
        options.add_argument(f"--window-size={settings.WINDOW_WIDTH},{settings.WINDOW_HEIGHT}")
        options.add_argument("--no-sandbox")
        options.add_argument("--disable-dev-shm-usage")
        options.add_argument("--disable-gpu")
        options.add_argument("--lang=zh-CN")
        options.add_argument("--log-level=3")
        options.add_experimental_option(
            "prefs",
            {
                "intl.accept_languages": "zh-CN,zh;q=0.9",
                "profile.default_content_setting_values.notifications": 2,
            },
        )
        return webdriver.Chrome(options=options)

    @staticmethod
    def _create_firefox() -> webdriver.Firefox:
        options = FirefoxOptions()
        if settings.HEADLESS:
            options.add_argument("--headless")
        options.add_argument(f"--width={settings.WINDOW_WIDTH}")
        options.add_argument(f"--height={settings.WINDOW_HEIGHT}")
        options.set_preference("intl.accept_languages", "zh-CN,zh;q=0.9")
        options.set_preference("dom.webnotifications.enabled", False)
        return webdriver.Firefox(options=options)

    @staticmethod
    def _create_edge() -> webdriver.Edge:
        options = EdgeOptions()
        if settings.HEADLESS:
            options.add_argument("--headless=new")
        options.add_argument(f"--window-size={settings.WINDOW_WIDTH},{settings.WINDOW_HEIGHT}")
        options.add_argument("--no-sandbox")
        options.add_argument("--disable-dev-shm-usage")
        options.add_argument("--lang=zh-CN")
        return webdriver.Edge(options=options)


def create_chrome_driver(headless: bool | None = None) -> webdriver.Chrome:
    """Create a Chrome driver, optionally overriding headless mode."""
    original = settings.HEADLESS
    if headless is not None:
        settings.HEADLESS = headless
    try:
        return DriverFactory.create_driver("chrome")
    finally:
        settings.HEADLESS = original
