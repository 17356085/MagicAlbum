# BlueAlbum Python tests

`tests/` is the canonical home for the Python automation suites. The commands
below describe how to collect or run the suites; they do not imply that every
environment or scenario has passed.

## Layout

- `tests/api/` contains HTTP API tests. They do not start a browser, but most
  authenticated cases need a dedicated test account.
- `tests/e2e/selenium/` contains the Selenium Page Objects and UI scenarios.
- `tests/e2e/playwright/` contains Playwright UI scenarios without a Selenium
  equivalent.
- `tests/conftest.py` owns the shared API, Selenium, and Playwright fixtures.
- [`TEST_PLAN.md`](TEST_PLAN.md) records the current test-plan outline.

The old `test/` and `py-selenium/` paths are not test roots in a fresh clone.
The migration table below is historical source mapping only; run the canonical
destinations above.

| Historical source | Canonical destination |
| --- | --- |
| `py-selenium/tests/test_auth.py` API classes | `tests/api/test_auth.py` |
| `py-selenium/tests/test_threads.py` API class | `tests/api/test_threads.py` |
| `test/tests/test_ai.py` | `tests/api/test_ai.py` |
| `test/tests/test_comments.py` API class | `tests/api/test_comments.py` |
| `test/tests/test_user.py` API class | `tests/api/test_user.py` |
| `py-selenium/tests/test_auth.py`, `test_threads.py`, `test_comments.py`, `test_navigation.py` UI classes | `tests/e2e/selenium/` |
| `test/tests/test_navigation.py`, `test_search.py`, `test_user.py` unique UI scenarios | `tests/e2e/playwright/` |
| `test/tests/test_threads.py` ranking scenario | `tests/e2e/playwright/test_threads.py` |

## Setup and configuration

Use Python 3.10 or newer and install the checked-in test dependencies from the
repository root:

```sh
python -m pip install -r tests/requirements.txt
python -m playwright install chromium
```

The second command installs Playwright's bundled Chromium. Skip it when using
an installed browser via `TEST_PLAYWRIGHT_CHANNEL=chrome`, or install the
browser channel separately according to your local environment.

Create a local environment file from [`tests/.env.example`](.env.example):

```sh
cp tests/.env.example tests/.env
# PowerShell: Copy-Item tests/.env.example tests/.env
```

The example leaves `TEST_EMAIL`, `TEST_PHONE`, and `TEST_PASSWORD` blank. Fill
them only with a dedicated test account when authenticated API or UI cases are
needed; no credentials are supplied by this repository and `tests/.env` must
not be committed.

The defaults in `tests/conftest.py` are:

- frontend: `http://localhost:5173`
- API gateway: `http://localhost:9000/api/v1`
- browser: headless Chrome through Selenium Manager, plus bundled Chromium for
  Playwright

The frontend and gateway must be running for API or E2E execution. The
repository launcher [`scripts/Start-Local.ps1`](../scripts/Start-Local.ps1)
starts the full local stack, but it requires its own JDK, Docker, Maven, and
Node prerequisites.

Useful overrides are `TEST_BASE_URL`, `TEST_API_BASE_URL`,
`TEST_PLAYWRIGHT_CHANNEL=chrome`, and `--api-url`. Selenium Manager resolves
the browser driver; do not add a checked-in driver binary or a
`webdriver-manager` dependency. Use `--selenium-headed` for a visible browser
run; the option affects the shared browser fixtures.

## Commands

Run from the repository root:

```text
python -m pytest -c tests/pytest.ini --collect-only -q -p no:cacheprovider tests
python -m pytest -c tests/pytest.ini tests/api -m api
python -m pytest -c tests/pytest.ini tests/e2e/selenium -m ui --selenium-browser=chrome
python -m pytest -c tests/pytest.ini tests/e2e/playwright -m ui
```

Collection does not require running services. API and E2E execution does, and
authenticated cases fail early when the test account variables are missing or
the account cannot log in. The suites can create or mutate test data, so use an
isolated local/test environment rather than a shared or production database.

Reports, screenshots, caches, `.env`, and Python bytecode are local run
products, not source artifacts.
