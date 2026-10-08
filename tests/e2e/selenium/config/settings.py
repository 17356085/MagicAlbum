"""Environment-backed Selenium settings."""

from __future__ import annotations

import os
from pathlib import Path

from dotenv import load_dotenv


ENV_FILE = Path(__file__).resolve().parents[3] / ".env"
if ENV_FILE.exists():
    load_dotenv(ENV_FILE)


class Settings:
    BASE_URL = os.getenv("TEST_BASE_URL", "http://localhost:5173").rstrip("/")
    API_BASE_URL = os.getenv("TEST_API_BASE_URL", "http://localhost:9000/api/v1").rstrip("/")

    BROWSER = os.getenv("TEST_BROWSER", "chrome").lower()
    HEADLESS = os.getenv("TEST_HEADLESS", "true").lower() == "true"
    WINDOW_WIDTH = int(os.getenv("TEST_VIEWPORT_WIDTH", "1280"))
    WINDOW_HEIGHT = int(os.getenv("TEST_VIEWPORT_HEIGHT", "900"))

    IMPLICIT_WAIT = int(os.getenv("TEST_IMPLICIT_WAIT", "3"))
    EXPLICIT_WAIT = int(os.getenv("TEST_EXPLICIT_WAIT", "15"))
    PAGE_LOAD_TIMEOUT = int(os.getenv("TEST_PAGE_LOAD_TIMEOUT", "30"))

    TEST_PHONE = os.getenv("TEST_PHONE", "")
    TEST_EMAIL = os.getenv("TEST_EMAIL", "")
    TEST_PASSWORD = os.getenv("TEST_PASSWORD", "")


settings = Settings()
