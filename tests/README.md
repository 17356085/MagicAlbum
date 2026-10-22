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

`tests/e2e/playwright/test_layout_fixtures.py` is an exception: it needs only
the frontend and a browser. All API responses are intercepted with fixed
fixtures, and no backend account is used. These 39 layout/keyboard checks
are not backend acceptance and are not part of mandatory16. Run against an
isolated frontend preview, for example in PowerShell:

```powershell
$env:TEST_BASE_URL = 'http://127.0.0.1:35174'
$env:TEST_PLAYWRIGHT_CHANNEL = 'chrome' # or use installed bundled Chromium
$env:TEST_LAYOUT_ARTIFACTS = 'outputs/layout-check/screenshots' # optional
python -m pytest tests/e2e/playwright/test_layout_fixtures.py
```

`test_mobile_tasks.py` adds 21 touch-emulated tasks at 320, 390, and 430px:
bottom navigation, focused composition and feedback, long-thread reply,
guest login, account actions, phone landscape, and filtered-list return. It shares the same
intercepted fixtures. Run both files for 60 frontend-only checks. They do
not prove physical-device soft-keyboard, Safari, or backend behavior.

Useful overrides are `TEST_BASE_URL`, `TEST_API_BASE_URL`,
`TEST_PLAYWRIGHT_CHANNEL=chrome`, and `--api-url`. Selenium Manager resolves
the browser driver; do not add a checked-in driver binary or a
`webdriver-manager` dependency. Use `--selenium-headed` for a visible browser
run; the option affects the shared browser fixtures.

## Commands

Run from the repository root:

```text
python -m pytest --collect-only -q -p no:cacheprovider tests
python -m pytest tests/api/test_ci_contract.py tests/api/test_bookmarks.py tests/api/test_comments.py tests/api/test_threads.py tests/api/test_user.py -m api
python -m pytest tests/e2e/selenium/test_ci_smoke.py -m ui --selenium-browser=chrome
python -m pytest tests/api -m api  # local regression inventory
python -m pytest tests/e2e/selenium -m ui --selenium-browser=chrome
python -m pytest tests/e2e/playwright -m ui
python -m pytest --collect-only -q -p no:cacheprovider tests/e2e/playwright/test_m0_journey.py
```

Collection does not require running services. API and E2E execution does, and
the suites can create or mutate test data, so use an isolated local/test
environment rather than a shared or production database.

The mandatory runtime API suite is the deterministic group
`tests/api/test_ci_contract.py`, `test_bookmarks.py`, `test_comments.py`,
`test_threads.py`, and `test_user.py`. Its session fixture creates two
UUID-based accounts, one owner thread, and one reader comment, then verifies
authenticated readback, bookmark and like transitions, cross-account
ownership rejection, anonymous write rejection, and the concurrent same-phone
registration contract (one `201`, one `409`, with a winner login/profile
readback). Setup failures are test failures; the fixture never converts
missing sections or failed API creation into `pytest.skip`. Teardown removes
mutable content and interaction state, while accounts remain because the
public contract has no account-delete operation.

The dedicated Selenium smoke at
`tests/e2e/selenium/test_ci_smoke.py` uses a separate generated account, seeded
thread, and seeded comment. It reads the thread in Selenium, submits a comment
through the UI, and reads that comment back through the API before cleanup.
The default fresh-stack gate keeps the deterministic API contract group,
the dedicated Selenium smoke, and the independent M0 Playwright journey
(mandatory16). A separate `python-full-regression` CI job runs the entire
canonical tree: currently 143 tests, including the 60 intercepted frontend
layout/mobile checks. The fixture checks do not become backend acceptance
merely because they share that job.

The full job explicitly sets `BLUEALBUM_FULL_PYTHON_REGRESSION=1` when invoking
`scripts/ci/compose-browser-smoke.sh`. It owns a disposable Compose project,
databases, account and seeded content; initializes that account's administrator
role through the supported bootstrap configuration; and verifies permissions
through the API. It forces the server-side Mock AI provider, uses no real model
credentials, and requires collection and executed JUnit counts to match with
zero failures, errors or skips. The original mandatory16 job remains separate.
Cleanup removes only that job's containers, volumes, networks and temporary
credential files. A cleanup failure fails the job.

Do not invoke the full suite against shared local data: it creates and mutates
accounts, posts, bookmarks, settings and moderation state. Ordinary local
commands still require the documented dedicated account and provider setup.

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

`.github/workflows/ci.yml` runs the `runtime-browser` job after the repository,
frontend, backend, and Python source checks. The job installs the checked-in Python
dependencies, warms Selenium Manager's Chrome resolution, and installs bundled
Playwright Chromium before invoking
[`scripts/ci/compose-browser-smoke.sh`](../scripts/ci/compose-browser-smoke.sh).
The script creates a unique Compose project named
`bluealbum-ci-<GITHUB_RUN_ID>-<GITHUB_RUN_ATTEMPT>`, refuses existing containers
or volumes with that project label, checks the loopback host ports, builds the
full Compose stack one application at a time, and waits up to 300 seconds for healthy services. A local
manual invocation uses a UTC timestamp project suffix.

The script creates CI-only credentials and `runtime.override.yml` under
`outputs/m0-ci`; it never reads the repository `.env`, prints secret values, or
uploads the generated env file. The override only sets the three Java heap
limits (`384m`, `256m`, `256m`) and two active JVM processors. The test receives
the stack URLs and an empty `TEST_PLAYWRIGHT_CHANNEL`, so CI uses bundled
Chromium. The script runs the mandatory API contract group, the dedicated
Selenium smoke, and the M0/R01/R04 Playwright journey against the same Compose
project. Each JUnit report requires at least one testcase and zero skipped,
failed, or errored cases. The Selenium step uses `DriverFactory` through
Selenium Manager to resolve Chrome and its driver; Playwright continues to use
its bundled Chromium. The cleanup trap saves project logs, runs
`docker compose down --volumes --remove-orphans`, verifies that only the
unique project resources are gone, and makes cleanup failure fail the job.

The uploaded CI artifact contains `api-junit.xml`, `selenium-junit.xml`,
`ui-junit.xml`, the compatibility copy `junit.xml`, failure screenshots, and
Compose logs. The legacy Selenium scenarios remain a separately runnable UI
suite: they use the shared externally supplied account fixture and several
historical data-dependent cases, so they remain outside this mandatory
fresh-stack gate. Full backup/restore, restart, image-byte,
and database recovery verification remains
the responsibility of the local [`scripts/Test-ComposeRuntime.ps1`](../scripts/Test-ComposeRuntime.ps1)
workflow.

The Windows workflow also executes the same API 14, Selenium 1, and Playwright
1 tests before restart and backup/restore verification. It writes
`api-junit.xml`, `selenium-junit.xml`, and the compatible `browser-results.xml`,
and rejects empty, failed, errored, or skipped reports. Builds run sequentially;
the backend build JVM defaults to a 512 MiB heap and eight cached Spring test
contexts, independently of the application runtime heap.

On hosts with limited memory, the Windows workflow accepts
`-ResourceMode Constrained -NoBuild` with separately built current-source
images matching the run ID. Constrained mode refuses to build, because its
budget applies to runtime services rather than Maven build processes. The
default `Standard` mode retains its 4 GiB free
commit-memory preflight. Constrained mode requires 2816 MiB: 1792 MiB of explicit
service limits, 384 MiB reserved for the browser, and 640 MiB of headroom. It
limits all seven services, sets each combined memory-plus-swap limit to the
same value, and tunes the disposable MySQL instance and JVMs.
Before restore, it stops the source applications, Redis, and RabbitMQ while
keeping source MySQL available for the database snapshot checks. The same API,
browser, restart, database hash, upload hash, and restored API checks still run.
During restore, the browser reservation instead covers the 320 MiB source
MySQL instance plus 64 MiB of phase allowance; the two full stacks never run
together.
This mode verifies correctness under resource limits; it is not a capacity test.

To exercise the same entry point manually on a host with Docker and the test
dependencies installed, run `bash scripts/ci/compose-browser-smoke.sh` from the
repository root. It uses the standard loopback ports unless `COMPOSE_*_PORT`
variables are supplied and removes its temporary credentials on exit.
