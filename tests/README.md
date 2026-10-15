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
- Root `pytest.ini` limits default collection to this canonical tree, preserving markers and avoiding historical runtime artifacts.
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
python -m pytest --collect-only -q -p no:cacheprovider tests
python -m pytest tests/api -m api
python -m pytest tests/e2e/selenium -m ui --selenium-browser=chrome
python -m pytest tests/e2e/playwright -m ui
python -m pytest --collect-only -q -p no:cacheprovider tests/e2e/playwright/test_m0_journey.py
```

Collection does not require running services. API and E2E execution does, and
authenticated cases fail early when the test account variables are missing or
the account cannot log in. The suites can create or mutate test data, so use an
isolated local/test environment rather than a shared or production database.

Reports, screenshots, caches, `.env`, and Python bytecode are local run
products, not source artifacts.

### M0/R01/R04 isolated browser journey

`test_m0_r01_r04_browser_journey` creates two UUID-based accounts and launches
its own headless Playwright context. Account A registers and logs in through the
browser UI; account B is registered through the API and then uses a second
browser context. The journey covers a browser-created thread and comment,
bookmark and unbookmark, notification navigation and read state, report
submission, and reverse ownership checks. Each durable result is read again
through the API. The test does not use the shared account fixtures.

Run it against the isolated Compose stack from the repository root. The
installed Chrome channel can be selected without installing a bundled browser:

```text
TEST_BASE_URL=http://127.0.0.1:35173 \
TEST_API_BASE_URL=http://127.0.0.1:39090/api/v1 \
TEST_PLAYWRIGHT_CHANNEL=chrome \
TEST_ARTIFACT_DIR=outputs/m0-test \
python -m pytest -q tests/e2e/playwright/test_m0_journey.py
```

On PowerShell, set the same variables with `$env:NAME = 'value'` before running
the pytest command. If the journey fails, it writes safe screenshots to
`TEST_ARTIFACT_DIR`; generated passwords, access tokens, and response bodies are
never written there. On a successful run, `fixture.json` contains only the
created owner/reader, thread, comment, report, and notification IDs for the
runner's post-run API or SQL readback.

The journey intentionally leaves the two generated accounts, notifications,
and report for auditability. It soft-deletes the generated thread, deletes the
generated comment, and removes the bookmark when those owner operations are
available. Run it only against a disposable isolated stack; the generated
accounts are not real user accounts and there is no public account-delete
operation in this test contract.

### CI runtime-browser job

`.github/workflows/ci.yml` runs the `runtime-browser` job after the frontend and
backend checks. The job installs the checked-in Python dependencies and bundled
Playwright Chromium, then invokes
[`scripts/ci/compose-browser-smoke.sh`](../scripts/ci/compose-browser-smoke.sh).
The script creates a unique Compose project named
`bluealbum-ci-<GITHUB_RUN_ID>-<GITHUB_RUN_ATTEMPT>`, refuses existing containers
or volumes with that project label, checks the loopback host ports, builds the
full Compose stack, and waits up to 300 seconds for healthy services. A local
manual invocation uses a UTC timestamp project suffix.

The script creates CI-only credentials and `runtime.override.yml` under
`outputs/m0-ci`; it never reads the repository `.env`, prints secret values, or
uploads the generated env file. The override only sets the three Java heap
limits (`384m`, `256m`, `256m`) and two active JVM processors. The test receives
the stack URLs and an empty `TEST_PLAYWRIGHT_CHANNEL`, so CI uses bundled
Chromium. The JUnit gate requires at least one testcase and zero skipped,
failed, or errored cases. The cleanup trap saves project logs, runs
`docker compose down --volumes --remove-orphans`, verifies that only the
unique project resources are gone, and makes cleanup failure fail the job.

The uploaded CI artifact contains the JUnit report, failure screenshots, and
Compose logs. This job covers the new browser journey in a fresh runtime. Full
backup/restore, restart, image-byte, and database recovery verification remains
the responsibility of the local [`scripts/Test-ComposeRuntime.ps1`](../scripts/Test-ComposeRuntime.ps1)
workflow.

To exercise the same entry point manually on a host with Docker and the test
dependencies installed, run `bash scripts/ci/compose-browser-smoke.sh` from the
repository root. It uses the standard loopback ports unless `COMPOSE_*_PORT`
variables are supplied and removes its temporary credentials on exit.
