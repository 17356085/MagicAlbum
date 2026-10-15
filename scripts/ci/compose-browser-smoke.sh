#!/usr/bin/env bash

set -Eeuo pipefail
IFS=$'\n\t'
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
cd -- "$ROOT_DIR"
ARTIFACT_DIR="${M0_CI_ARTIFACT_DIR:-$ROOT_DIR/outputs/m0-ci}"
ARTIFACT_DIR="$(python - "$ROOT_DIR" "$ARTIFACT_DIR" <<'PY'
from pathlib import Path
import sys
allowed = (Path(sys.argv[1]) / "outputs").resolve()
candidate = Path(sys.argv[2]).resolve()
if candidate == allowed or not candidate.is_relative_to(allowed):
    raise SystemExit("CI artifacts must be under workspace outputs")
print(candidate)
PY
)"
ENV_FILE="$ARTIFACT_DIR/ci-runtime.env"
OVERRIDE_FILE="$ARTIFACT_DIR/runtime.override.yml"
LOG_DIR="$ARTIFACT_DIR/logs"
SCREENSHOT_DIR="$ARTIFACT_DIR/screenshots"
JUNIT_FILE="$ARTIFACT_DIR/junit.xml"

PROJECT=""
COMPOSE_TOUCHED=0
CREDENTIALS_CREATED=0

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

if [[ -n "${GITHUB_RUN_ID:-}" || -n "${GITHUB_RUN_ATTEMPT:-}" ]]; then
  [[ "${GITHUB_RUN_ID:-}" =~ ^[0-9]+$ ]] || die 'GITHUB_RUN_ID must contain only digits in CI'
  [[ "${GITHUB_RUN_ATTEMPT:-}" =~ ^[0-9]+$ ]] || die 'GITHUB_RUN_ATTEMPT must contain only digits in CI'
  PROJECT="bluealbum-ci-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
else
  PROJECT="bluealbum-ci-$(date -u +%Y%m%d%H%M%S)-$$"
fi

if [[ ! "$PROJECT" =~ ^bluealbum-ci-[a-z0-9-]+$ ]]; then
  die "invalid Compose project name: $PROJECT"
fi

COMPOSE_ARGS=(
  --project-directory "$ROOT_DIR"
  --env-file "$ENV_FILE"
  --project-name "$PROJECT"
  --file "$ROOT_DIR/docker-compose.yml"
  --file "$OVERRIDE_FILE"
)

compose() {
  COMPOSE_DISABLE_ENV_FILE=1 docker compose "${COMPOSE_ARGS[@]}" "$@"
}

assert_project_unused() {
  local containers volumes
  if ! containers="$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT")"; then
    die 'cannot inspect Docker containers before starting the CI stack'
  fi
  if ! volumes="$(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT")"; then
    die 'cannot inspect Docker volumes before starting the CI stack'
  fi
  if [[ -n "$containers" || -n "$volumes" ]]; then
    die "Compose project label already has resources: $PROJECT"
  fi
}

validate_port() {
  local port="$1"
  [[ "$port" =~ ^[0-9]+$ ]] || die "invalid Compose host port: $port"
  (( port >= 1 && port <= 65535 )) || die "Compose host port is outside 1..65535: $port"
}

port_is_occupied() {
  local port="$1"
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    exec 3>&-
    return 0
  fi
  return 1
}

random_hex_32() {
  local value
  command -v openssl >/dev/null 2>&1 || die 'openssl is required to create CI-only secrets'
  value="$(openssl rand -hex 32 2>/dev/null)" || die 'failed to create a CI-only secret'
  [[ "$value" =~ ^[0-9a-f]{64}$ ]] || die 'openssl returned an invalid secret shape'
  printf '%s' "$value"
}

validate_junit() {
  local report="$1"
  python - "$report" <<'PY'
import sys
import xml.etree.ElementTree as ET

report_path = sys.argv[1]
try:
    root = ET.parse(report_path).getroot()
except (OSError, ET.ParseError) as exc:
    raise SystemExit(f"cannot parse JUnit report: {exc}")

cases = root.findall(".//testcase")
skipped = sum(case.find("skipped") is not None for case in cases)
failed = sum(case.find("failure") is not None for case in cases)
errors = sum(case.find("error") is not None for case in cases)
if len(cases) < 1 or skipped or failed or errors:
    raise SystemExit(
        f"JUnit gate failed: tests={len(cases)} skipped={skipped} failures={failed} errors={errors}"
    )
print(f"JUnit gate passed: tests={len(cases)} skipped=0 failures=0 errors=0")
PY
}

cleanup() {
  local original_status=$?
  local cleanup_status=0
  local remaining remaining_volumes

  set +e
  mkdir -p "$LOG_DIR" || cleanup_status=1
  if (( COMPOSE_TOUCHED == 1 )); then
    compose logs --no-color --timestamps --tail=500 >"$LOG_DIR/compose.log" 2>&1 || cleanup_status=1
    compose down --volumes --remove-orphans >"$LOG_DIR/compose-down.log" 2>&1 || cleanup_status=1

    if ! remaining="$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT" 2>/dev/null)"; then
      cleanup_status=1
    elif [[ -n "$remaining" ]]; then
      printf 'Owned containers remain after cleanup for %s\n' "$PROJECT" >"$LOG_DIR/cleanup-error.log"
      cleanup_status=1
    fi
    if ! remaining_volumes="$(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT" 2>/dev/null)"; then
      cleanup_status=1
    elif [[ -n "$remaining_volumes" ]]; then
      printf 'Owned volumes remain after cleanup for %s\n' "$PROJECT" >>"$LOG_DIR/cleanup-error.log"
      cleanup_status=1
    fi
  fi

  if (( CREDENTIALS_CREATED == 1 )) && [[ -f "$ENV_FILE" ]]; then
    rm -f -- "$ENV_FILE" || cleanup_status=1
  fi

  if (( cleanup_status != 0 )); then
    printf 'ERROR: Compose cleanup failed for %s; inspect %s\n' "$PROJECT" "$LOG_DIR" >&2
    original_status=1
  fi
  trap - EXIT
  exit "$original_status"
}

trap cleanup EXIT

assert_project_unused

mkdir -p "$ARTIFACT_DIR" "$LOG_DIR" "$SCREENSHOT_DIR"
[[ ! -e "$ENV_FILE" ]] || die "temporary credential file already exists: $ENV_FILE"

mysql_port="${COMPOSE_MYSQL_PORT:-3306}"
redis_port="${COMPOSE_REDIS_PORT:-6379}"
rabbitmq_port="${COMPOSE_RABBITMQ_PORT:-5672}"
rabbitmq_management_port="${COMPOSE_RABBITMQ_MANAGEMENT_PORT:-15672}"
backend_port="${COMPOSE_BACKEND_PORT:-8080}"
notification_port="${COMPOSE_NOTIFICATION_PORT:-8081}"
gateway_port="${COMPOSE_GATEWAY_PORT:-9000}"
frontend_port="${COMPOSE_FRONTEND_PORT:-5173}"

port_names=(mysql redis rabbitmq rabbitmq-management backend notification gateway frontend)
port_values=(
  "$mysql_port" "$redis_port" "$rabbitmq_port" "$rabbitmq_management_port"
  "$backend_port" "$notification_port" "$gateway_port" "$frontend_port"
)
for index in "${!port_values[@]}"; do
  port="${port_values[$index]}"
  validate_port "$port"
  if port_is_occupied "$port"; then
    die "Compose host port is occupied (${port_names[$index]}:$port); use a clean CI runner or set COMPOSE_*_PORT"
  fi
done

db_password="$(random_hex_32)"
db_root_password="$(random_hex_32)"
redis_password="$(random_hex_32)"
rabbitmq_password="$(random_hex_32)"
jwt_secret="$(random_hex_32)"
internal_service_token="$(random_hex_32)"

CREDENTIALS_CREATED=1
cat >"$ENV_FILE" <<EOF
DB_NAME=bluealbum_ci
NOTIFICATION_DB_NAME=bluealbum_ci_notifications
DB_USER=bluealbum_ci
DB_PASSWORD=$db_password
DB_ROOT_PASSWORD=$db_root_password
REDIS_PASSWORD=$redis_password
RABBITMQ_USER=bluealbum_ci
RABBITMQ_PASSWORD=$rabbitmq_password
JWT_ISSUER=MagicAlbum
JWT_SECRET=$jwt_secret
APP_ADMIN_EMAIL=
INTERNAL_SERVICE_TOKEN=$internal_service_token
COMPOSE_MYSQL_PORT=$mysql_port
COMPOSE_REDIS_PORT=$redis_port
COMPOSE_RABBITMQ_PORT=$rabbitmq_port
COMPOSE_RABBITMQ_MANAGEMENT_PORT=$rabbitmq_management_port
COMPOSE_BACKEND_PORT=$backend_port
COMPOSE_NOTIFICATION_PORT=$notification_port
COMPOSE_GATEWAY_PORT=$gateway_port
COMPOSE_FRONTEND_PORT=$frontend_port
EOF
chmod 600 "$ENV_FILE"
# Compose prioritizes inherited shell values over --env-file, including empty values.
while IFS='=' read -r key _; do
  unset "$key"
done < "$ENV_FILE"

cat >"$OVERRIDE_FILE" <<'EOF'
services:
  backend:
    environment:
      JAVA_OPTS: "-Xmx384m -XX:ActiveProcessorCount=2"
  notification-service:
    environment:
      JAVA_OPTS: "-Xmx256m -XX:ActiveProcessorCount=2"
  gateway:
    environment:
      JAVA_OPTS: "-Xmx256m -XX:ActiveProcessorCount=2"
EOF

COMPOSE_TOUCHED=1
compose config --quiet
compose build
compose up -d --wait --wait-timeout 300

export TEST_BASE_URL="http://127.0.0.1:$frontend_port"
export TEST_API_BASE_URL="http://127.0.0.1:$gateway_port/api/v1"
export TEST_PLAYWRIGHT_CHANNEL=""
export TEST_ARTIFACT_DIR="$SCREENSHOT_DIR"
export TEST_EMAIL=''
export TEST_PHONE=''
export TEST_PASSWORD=''

set +e
python -m pytest -c pytest.ini tests/e2e/playwright/test_m0_journey.py -q --junitxml "$JUNIT_FILE"
pytest_status=$?
set -e

[[ -f "$JUNIT_FILE" ]] || die "pytest did not produce a JUnit report: $JUNIT_FILE"
validate_junit "$JUNIT_FILE"
(( pytest_status == 0 )) || die "M0 browser journey exited with status $pytest_status"

printf 'M0 browser Compose smoke passed for %s\n' "$PROJECT"
