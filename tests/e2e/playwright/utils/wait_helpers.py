"""Explicit waits used by the canonical Playwright Page Objects."""

from __future__ import annotations

import re

from playwright.sync_api import Locator, Page, TimeoutError as PlaywrightTimeoutError


def wait_for_vue_route(page: Page, url_pattern: str, timeout: float = 10000) -> None:
    """Wait for Vue Router navigation and document readiness."""
    page.wait_for_url(re.compile(url_pattern), timeout=timeout)
    page.wait_for_load_state("domcontentloaded")
    page.wait_for_load_state("networkidle")


def wait_for_loading_gone(page: Page, timeout: float = 15000) -> None:
    """Wait until each known loading label is absent from the document."""
    for loading_text in ("正在加载...", "加载中...", "Loading..."):
        page.wait_for_function(
            "text => !document.body.innerText.includes(text)",
            arg=loading_text,
            timeout=timeout,
        )


def wait_for_text_present(page: Page, text: str, timeout: float = 10000) -> None:
    """Wait until the given text is present in the document."""
    page.wait_for_function(
        "value => document.body.innerText.includes(value)", arg=text, timeout=timeout
    )


def wait_for_element_stable(locator: Locator, timeout: float = 5000) -> Locator:
    """Wait for a visible element to stop moving."""
    locator.wait_for(state="visible", timeout=timeout)
    page = locator.page
    previous_box = locator.bounding_box()
    page.wait_for_timeout(100)
    for _ in range(10):
        current_box = locator.bounding_box()
        if previous_box == current_box:
            break
        previous_box = current_box
        page.wait_for_timeout(50)
    return locator


def wait_for_vue_conditional(page: Page, css_selector: str, timeout: float = 10000) -> Locator:
    """Wait for a v-if element to be attached to the DOM."""
    locator = page.locator(css_selector)
    locator.wait_for(state="attached", timeout=timeout)
    return locator


def wait_for_v_else_gone(page: Page, css_selector: str, timeout: float = 5000) -> None:
    """Wait for a v-if loading element to be detached."""
    page.locator(css_selector).wait_for(state="detached", timeout=timeout)


def wait_for_optimistic_update(page: Page, unique_text: str, timeout: float = 10000) -> None:
    """Wait until an optimistic update is rendered without a temporary ID."""
    page.wait_for_function(
        "value => document.body.innerText.includes(value) && !document.body.innerText.includes('temp_')",
        arg=unique_text,
        timeout=timeout,
    )


def safe_click_when_enabled(locator: Locator, timeout: float = 5000) -> None:
    """Wait for a visible, enabled locator and click it."""
    locator.click(timeout=timeout)


def is_visible_after_animation(locator: Locator, timeout: float = 3000) -> bool:
    """Return whether a locator becomes visible before the timeout."""
    try:
        locator.wait_for(state="visible", timeout=timeout)
        return locator.is_visible()
    except PlaywrightTimeoutError:
        return False
