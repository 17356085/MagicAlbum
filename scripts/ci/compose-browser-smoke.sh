#!/usr/bin/env bash

set -Eeuo pipefail
IFS=$'\n\t'
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
cd -- "$ROOT_DIR"
if [[ -z "${PYTHON_BIN:-}" ]]; then
  if command -v python >/dev/null 2>&1; then
    PYTHON_BIN=python
  elif command -v python3 >/dev/null 2>&1; then
    PYTHON_BIN=python3
  else
    printf 'ERROR: Python 3 is required for the CI gate\n' >&2
    exit 1
  fi
fi
FULL_MODE="${BLUEALBUM_FULL_PYTHON_REGRESSION:-0}"
SPLIT_CHAT="${BLUEALBUM_SPLIT_CHAT_CHECK:-0}"
CUTOVER_MODE="${BLUEALBUM_CHAT_CUTOVER_CHECK:-0}"
CHAT_REALTIME_CHECK="${BLUEALBUM_CHAT_REALTIME_CHECK:-0}"
CHAT_BACKUP_CHECK="${BLUEALBUM_CHAT_BACKUP_CHECK:-0}"
CHAT_AUTH_TIMING_CHECK="${BLUEALBUM_CHAT_AUTH_TIMING_CHECK:-0}"
CHAT_POLL_LOAD="${BLUEALBUM_CHAT_POLL_LOAD_CHECK:-0}"
CHAT_GRPC_CHECK="${BLUEALBUM_CHAT_GRPC_CHECK:-0}"
TELEMETRY_CHECK="${BLUEALBUM_TELEMETRY_CHECK:-0}"
RPC_LOAD_CHECK="${BLUEALBUM_CHAT_RPC_LOAD_CHECK:-0}"
[[ "$RPC_LOAD_CHECK" =~ ^[01]$ ]] || {
  printf 'ERROR: BLUEALBUM_CHAT_RPC_LOAD_CHECK must be 0 or 1\n' >&2
  exit 1
}
if [[ "$RPC_LOAD_CHECK" == 1 ]]; then CHAT_GRPC_CHECK=1; fi
COMMENT_EVENTS_CHECK="${BLUEALBUM_COMMENT_EVENTS_CHECK:-0}"
[[ "$CHAT_GRPC_CHECK" =~ ^[01]$ ]] || {
  printf 'ERROR: BLUEALBUM_CHAT_GRPC_CHECK must be 0 or 1\n' >&2
  exit 1
}
if [[ "$CHAT_GRPC_CHECK" == 1 ]]; then SPLIT_CHAT=1; fi
[[ "$TELEMETRY_CHECK" =~ ^[01]$ ]] || {
  printf 'ERROR: BLUEALBUM_TELEMETRY_CHECK must be 0 or 1\n' >&2
  exit 1
}
if [[ "$TELEMETRY_CHECK" == 1 ]]; then SPLIT_CHAT=1; fi
[[ "$COMMENT_EVENTS_CHECK" =~ ^[01]$ ]] || {
  printf 'ERROR: BLUEALBUM_COMMENT_EVENTS_CHECK must be 0 or 1\n' >&2
  exit 1
}
[[ "$CHAT_POLL_LOAD" == "0" || "$CHAT_POLL_LOAD" == "1" ]] || {
  printf 'ERROR: BLUEALBUM_CHAT_POLL_LOAD_CHECK must be 0 or 1\n' >&2
  exit 1
}
if [[ "$CHAT_POLL_LOAD" == "1" ]]; then CUTOVER_MODE=1; fi
[[ "$CUTOVER_MODE" == "0" || "$CUTOVER_MODE" == "1" ]] || {
  printf 'ERROR: BLUEALBUM_CHAT_CUTOVER_CHECK must be 0 or 1\n' >&2
  exit 1
}
[[ "$SPLIT_CHAT" == "0" || "$SPLIT_CHAT" == "1" ]] || {
  printf 'ERROR: BLUEALBUM_SPLIT_CHAT_CHECK must be 0 or 1\n' >&2
  exit 1
}
[[ "$FULL_MODE" == "0" || "$FULL_MODE" == "1" ]] || {
  printf 'ERROR: BLUEALBUM_FULL_PYTHON_REGRESSION must be 0 or 1\n' >&2
  exit 1
}
[[ "$CHAT_REALTIME_CHECK" == "0" || "$CHAT_REALTIME_CHECK" == "1" ]] || {
  printf 'ERROR: BLUEALBUM_CHAT_REALTIME_CHECK must be 0 or 1\n' >&2
  exit 1
}
[[ "$CHAT_BACKUP_CHECK" == "0" || "$CHAT_BACKUP_CHECK" == "1" ]] || {
  printf 'ERROR: BLUEALBUM_CHAT_BACKUP_CHECK must be 0 or 1\n' >&2
  exit 1
}
[[ "$CHAT_AUTH_TIMING_CHECK" =~ ^[01]$ ]] || {
  printf 'ERROR: BLUEALBUM_CHAT_AUTH_TIMING_CHECK must be 0 or 1\n' >&2
  exit 1
}
if [[ "$CHAT_AUTH_TIMING_CHECK" == "1" ]]; then
  if [[ "$CUTOVER_MODE" == "1" || "$FULL_MODE" == "1" || "$CHAT_REALTIME_CHECK" == "1" || "$CHAT_BACKUP_CHECK" == "1" ]]; then
    printf 'ERROR: run the chat authorization timing check in its own project\n' >&2
    exit 1
  fi
  SPLIT_CHAT=1
  ARTIFACT_DIR="${M0_CI_ARTIFACT_DIR:-$ROOT_DIR/outputs/chat-auth-timing-ci}"
elif [[ "$CHAT_BACKUP_CHECK" == "1" ]]; then
  if [[ "$CUTOVER_MODE" == "1" || "$FULL_MODE" == "1" || "$CHAT_REALTIME_CHECK" == "1" ]]; then
    printf 'ERROR: run the chat backup check in its own project\n' >&2
    exit 1
  fi
  SPLIT_CHAT=1
  ARTIFACT_DIR="${M0_CI_ARTIFACT_DIR:-$ROOT_DIR/outputs/chat-backup-ci}"
elif [[ "$CHAT_REALTIME_CHECK" == "1" ]]; then
  if [[ "$CUTOVER_MODE" == "1" || "$FULL_MODE" == "1" ]]; then
    printf 'ERROR: run the chat realtime, cutover, and full regression checks in separate projects\n' >&2
    exit 1
  fi
  SPLIT_CHAT=1
  ARTIFACT_DIR="${M0_CI_ARTIFACT_DIR:-$ROOT_DIR/outputs/chat-realtime-ci}"
elif [[ "$CUTOVER_MODE" == "1" ]]; then
  SPLIT_CHAT=1
  ARTIFACT_DIR="${M0_CI_ARTIFACT_DIR:-$ROOT_DIR/outputs/chat-cutover-ci}"
elif [[ "$SPLIT_CHAT" == "1" ]]; then
  ARTIFACT_DIR="${M0_CI_ARTIFACT_DIR:-$ROOT_DIR/outputs/split-chat-ci}"
elif [[ "$FULL_MODE" == "1" ]]; then
  ARTIFACT_DIR="${M0_CI_ARTIFACT_DIR:-$ROOT_DIR/outputs/python-full-ci}"
else
  ARTIFACT_DIR="${M0_CI_ARTIFACT_DIR:-$ROOT_DIR/outputs/m0-ci}"
fi
ARTIFACT_DIR="$("$PYTHON_BIN" - "$ROOT_DIR" "$ARTIFACT_DIR" <<'PY'
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
API_JUNIT_FILE="$ARTIFACT_DIR/api-junit.xml"
SELENIUM_JUNIT_FILE="$ARTIFACT_DIR/selenium-junit.xml"
UI_JUNIT_FILE="$ARTIFACT_DIR/ui-junit.xml"
FULL_CREDENTIALS_FILE="$ARTIFACT_DIR/full-regression.env"
FULL_EVIDENCE_FILE="$ARTIFACT_DIR/full-regression-bootstrap.json"
FULL_COLLECT_FILE="$ARTIFACT_DIR/full-collect.txt"
FULL_JUNIT_FILE="$ARTIFACT_DIR/full-python-junit.xml"
CHAT_CUTOVER_STATE="$ARTIFACT_DIR/chat-cutover-state.json"
CHAT_CUTOVER_OVERRIDE="$ARTIFACT_DIR/chat-cutover.override.yml"
CHAT_CUTOVER_EVIDENCE="$ARTIFACT_DIR/chat-cutover-checks.txt"

PROJECT=""
COMPOSE_TOUCHED=0
CREDENTIALS_CREATED=0
FULL_CREDENTIALS_CREATED=0
CUTOVER_STATE_OWNED=0

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
if [[ "$FULL_MODE" == "1" ]]; then
  PROJECT="${PROJECT}-full"
fi
if [[ "$SPLIT_CHAT" == "1" ]]; then
  PROJECT="${PROJECT}-split-chat"
fi
if [[ "$CUTOVER_MODE" == "1" ]]; then
  PROJECT="${PROJECT}-cutover"
  source "$SCRIPT_DIR/compose-chat-cutover.sh"
fi
if [[ "$CHAT_REALTIME_CHECK" == "1" ]]; then
  PROJECT="${PROJECT}-realtime"
  source "$SCRIPT_DIR/compose-chat-realtime.sh"
fi
if [[ "$CHAT_AUTH_TIMING_CHECK" == "1" ]]; then
  PROJECT="${PROJECT}-auth-timing"
  source "$SCRIPT_DIR/compose-chat-permissions.sh"
fi
if [[ "$CHAT_BACKUP_CHECK" == "1" ]]; then
  PROJECT="${PROJECT}-backup"
  BACKUP_RESTORE_PROJECT="${PROJECT}-restore"
  BACKUP_RESTORE_ENV="$ARTIFACT_DIR/ci-restore.env"
  BACKUP_RESTORE_ENV_OWNED=0
  BACKUP_RESTORE_TOUCHED=0
  source "$SCRIPT_DIR/compose-chat-backup.sh"
fi

if [[ ! "$PROJECT" =~ ^bluealbum-ci-[a-z0-9-]+$ ]]; then
  die "invalid Compose project name: $PROJECT"
fi

COMPOSE_ARGS=(
  --project-directory "$ROOT_DIR"
  --env-file "$ENV_FILE"
  --project-name "$PROJECT"
  --file "$ROOT_DIR/docker-compose.yml"
)
if [[ "$SPLIT_CHAT" == "1" ]]; then
  COMPOSE_ARGS+=(--file "$ROOT_DIR/docker-compose.chat.yml")
fi
if [[ "$CHAT_REALTIME_CHECK" == "1" ]]; then
  COMPOSE_ARGS+=(--file "$ROOT_DIR/docker-compose.chat-observability.yml")
fi
GRPC_FIXTURE_DIR=""
if [[ "$CHAT_GRPC_CHECK" == 1 ]]; then
  COMPOSE_ARGS+=(--file "$ROOT_DIR/docker-compose.grpc.yml")
fi
if [[ "$TELEMETRY_CHECK" == 1 ]]; then
  COMPOSE_ARGS+=(--file "$ROOT_DIR/docker-compose.telemetry.yml")
fi
if [[ "$COMMENT_EVENTS_CHECK" == 1 ]]; then
  COMPOSE_ARGS+=(--file "$ROOT_DIR/docker-compose.comment-events.yml")
fi
COMPOSE_ARGS+=(--file "$OVERRIDE_FILE")

compose() {
  COMPOSE_DISABLE_ENV_FILE=1 docker compose "${COMPOSE_ARGS[@]}" "$@"
}

verify_split_chat_owner() {
  [[ "$SPLIT_CHAT" == "1" ]] || return 0
  local required_messages="${1:-34}" core_rows split_chat_messages chat_migrations ddl_attempt cross_db_attempt
  compose exec -T chat-service sh -ec 'test -z "${JWT_SECRET:-}" && test -z "${DB_PASSWORD:-}"' \
    || die 'Stand-alone chat process received core credentials'
  core_rows=$(compose exec -T mysql sh -c '
    MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -Nse "
      SELECT (SELECT COUNT(*) FROM chat_conversations) + (SELECT COUNT(*) FROM chat_members)
           + (SELECT COUNT(*) FROM chat_messages) + (SELECT COUNT(*) FROM chat_daily_usage)
           + (SELECT COUNT(*) FROM chat_preferences)"
  ')
  split_chat_messages=$(compose exec -T chat-mysql sh -c '
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root "$MYSQL_DATABASE" -Nse "SELECT COUNT(*) FROM chat_messages"
  ')
  chat_migrations=$(compose exec -T chat-mysql sh -c '
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root "$MYSQL_DATABASE" -Nse "SELECT COUNT(*) FROM chat_flyway_schema_history WHERE success = 1 AND version IN (\"1\",\"2\",\"3\",\"4\")"
  ')
  if ddl_attempt=$(compose exec -T chat-mysql sh -c '
    MYSQL_PWD="$CHAT_DB_PASSWORD" exec mysql -u "$CHAT_DB_USER" "$CHAT_DB_NAME" -Nse "CREATE TABLE ci_chat_runtime_ddl_probe (id INT)"
  ' 2>&1); then
    die 'Chat runtime database account could create a table'
  fi
  [[ "$ddl_attempt" == *'ERROR 1142'* ]] || die 'Chat runtime DDL probe did not explicitly fail on permissions'
  if cross_db_attempt=$(compose exec -T mysql sh -c '
    MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -h chat-mysql --connect-timeout=5 -u "$MYSQL_USER" bluealbum_ci_chat -Nse "SELECT COUNT(*) FROM chat_messages"
  ' 2>&1); then
    die 'Core runtime credentials could read the independent chat database'
  fi
  [[ "$cross_db_attempt" =~ ERROR\ (1044|1045) ]] || die 'Core-to-chat database probe did not explicitly fail on permissions'
  if cross_db_attempt=$(compose exec -T chat-mysql sh -c '
    MYSQL_PWD="$CHAT_DB_PASSWORD" exec mysql -h mysql --connect-timeout=5 -u "$CHAT_DB_USER" bluealbum_ci -Nse "SELECT COUNT(*) FROM users"
  ' 2>&1); then
    die 'Chat runtime credentials could read the core database'
  fi
  [[ "$cross_db_attempt" =~ ERROR\ (1044|1045) ]] || die 'Chat-to-core database probe did not explicitly fail on permissions'
  core_rows="${core_rows//$'\r'/}"
  split_chat_messages="${split_chat_messages//$'\r'/}"
  chat_migrations="${chat_migrations//$'\r'/}"
  [[ "$core_rows" == "0" ]] || die 'Core chat tables received writes during split-chat verification'
  [[ "$chat_migrations" == "4" ]] || die 'Stand-alone chat database did not apply the expected chat-owned migrations'
  if ! [[ "$split_chat_messages" =~ ^[1-9][0-9]*$ ]] || (( split_chat_messages < required_messages )); then
    die 'Stand-alone chat database is missing verified messages'
  fi
  printf 'core_chat_rows=%s\nsplit_chat_messages=%s\nchat_migrations=%s\ncore_credentials=absent\nruntime_ddl=denied\ncore_to_chat_database=denied\nchat_to_core_database=denied\n' \
    "$core_rows" "$split_chat_messages" "$chat_migrations" > "$ARTIFACT_DIR/chat-owner-checks.txt"
}

assert_project_unused() {
  local project="${1:-$PROJECT}" containers volumes networks
  if ! containers="$(docker ps -aq --filter "label=com.docker.compose.project=$project")"; then
    die 'cannot inspect Docker containers before starting the CI stack'
  fi
  if ! volumes="$(docker volume ls -q --filter "label=com.docker.compose.project=$project")"; then
    die 'cannot inspect Docker volumes before starting the CI stack'
  fi
  if ! networks="$(docker network ls -q --filter "label=com.docker.compose.project=$project")"; then
    die 'cannot inspect Docker networks before starting the CI stack'
  fi
  if [[ -n "$containers" || -n "$volumes" || -n "$networks" ]]; then
    die "Compose project label already has containers, volumes, or networks: $project"
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
  local expected="$2"
  local required_section_page="${3:-}"
  local required_chat_backlog="${4:-}"
  "$PYTHON_BIN" - "$report" "$expected" "$required_section_page" "$required_chat_backlog" <<'PY'
import sys
import xml.etree.ElementTree as ET

report_path = sys.argv[1]
try:
    expected = int(sys.argv[2])
except (IndexError, ValueError):
    raise SystemExit("JUnit gate requires a positive expected testcase count")
if expected < 1:
    raise SystemExit("JUnit gate requires a positive expected testcase count")
try:
    root = ET.parse(report_path).getroot()
except (OSError, ET.ParseError) as exc:
    raise SystemExit(f"cannot parse JUnit report: {exc}")

cases = root.findall(".//testcase")
skipped = sum(case.find("skipped") is not None for case in cases)
failed = sum(case.find("failure") is not None for case in cases)
errors = sum(case.find("error") is not None for case in cases)
suites = list(root.iter("testsuite"))
invalid_summary = any(int(suite.get(field, "0")) != 0 for suite in suites for field in ("failures", "errors", "skipped"))
if len(cases) < 1 or skipped or failed or errors or invalid_summary:
    raise SystemExit(
        f"JUnit gate failed: tests={len(cases)} expected={expected} skipped={skipped} failures={failed} errors={errors}"
    )
if len(cases) != expected:
    raise SystemExit(f"JUnit gate failed: tests={len(cases)} expected={expected} skipped=0 failures=0 errors=0")
if sys.argv[3]:
    properties = root.findall(".//property[@name='thread_editor_section_page']")
    if [item.get("value") for item in properties] != [sys.argv[3]]:
        raise SystemExit("M0 browser journey did not verify posting and editing with second-page sections")
    properties = root.findall(".//property[@name='owned_section_filter_page']")
    if [item.get("value") for item in properties] != [sys.argv[3]]:
        raise SystemExit("M0 browser journey did not verify both owned section filters")
if sys.argv[4]:
    properties = root.findall(".//property[@name='realtime_chat_offline_backlog']")
    if [item.get("value") for item in properties] != [sys.argv[4]]:
        raise SystemExit("Browser journey did not verify the realtime chat offline backlog")
    properties = root.findall(".//property[@name='realtime_chat_connected_signal_loss']")
    if [item.get("value") for item in properties] != ["ordered_without_reconnect"]:
        raise SystemExit("Browser journey did not verify signal loss on an open WebSocket")
    recovery = [item.get("value", "") for item in root.findall(".//property[@name='realtime_chat_connected_recovery_ms']")]
    if len(recovery) != 1 or not recovery[0].isdecimal() or not 0 < int(recovery[0]) <= 15_000:
        raise SystemExit("Browser journey did not record recovery within its polling deadline")
    properties = root.findall(".//property[@name='friend_removal_browser']")
    if [item.get("value") for item in properties] != ["peer_follow_preserved"]:
        raise SystemExit("Browser journey did not verify friend removal preserves the peer's follow")
print(f"JUnit gate passed: tests={len(cases)} expected={expected} skipped=0 failures=0 errors=0")
PY
}

cleanup() {
  local original_status=$?
  local cleanup_status=0
  local remaining remaining_volumes remaining_networks

  set +e
  mkdir -p "$LOG_DIR" || cleanup_status=1
  if [[ "$CHAT_BACKUP_CHECK" == "1" ]]; then
    cleanup_chat_backup || cleanup_status=1
  fi
  if (( COMPOSE_TOUCHED == 1 )); then
    if [[ "${RPC_LOAD_CHECK:-0}" == 1 ]]; then
      local load_nodes load_node_ids
      load_nodes="$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT" \
        --filter "label=com.docker.compose.service=chat-realtime" \
        --filter 'label=com.docker.compose.oneoff=True')" || cleanup_status=1
      if [[ -n "$load_nodes" ]]; then
        mapfile -t load_node_ids <<< "$load_nodes"
        docker rm -f "${load_node_ids[@]}" >"$LOG_DIR/rpc-load-cleanup.log" 2>&1 || cleanup_status=1
      fi
    fi
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
    if ! remaining_networks="$(docker network ls -q --filter "label=com.docker.compose.project=$PROJECT" 2>/dev/null)"; then
      cleanup_status=1
    elif [[ -n "$remaining_networks" ]]; then
      printf 'Owned networks remain after cleanup for %s\n' "$PROJECT" >>"$LOG_DIR/cleanup-error.log"
      cleanup_status=1
    fi
  fi

  if (( CREDENTIALS_CREATED == 1 )) && [[ -f "$ENV_FILE" ]]; then
    rm -f -- "$ENV_FILE" || cleanup_status=1
  fi
  if (( FULL_CREDENTIALS_CREATED == 1 )) && [[ -f "$FULL_CREDENTIALS_FILE" ]]; then
    rm -f -- "$FULL_CREDENTIALS_FILE" || cleanup_status=1
  fi
  if [[ -n "$GRPC_FIXTURE_DIR" ]]; then
    rm -f -- "$GRPC_FIXTURE_DIR/tls.crt" "$GRPC_FIXTURE_DIR/tls.key" || cleanup_status=1
    rmdir -- "$GRPC_FIXTURE_DIR" || cleanup_status=1
  fi
  if (( CUTOVER_STATE_OWNED == 1 )); then
    rm -f -- "$CHAT_CUTOVER_STATE" "${CHAT_CUTOVER_STATE%.json}.tmp" || cleanup_status=1
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
"$PYTHON_BIN" - "$ARTIFACT_DIR/source-revision.json" "$PROJECT" "$FULL_MODE" "$SPLIT_CHAT" "$CUTOVER_MODE" \
  "$CHAT_REALTIME_CHECK" "$CHAT_BACKUP_CHECK" "$CHAT_AUTH_TIMING_CHECK" "$CHAT_GRPC_CHECK" \
  "$TELEMETRY_CHECK" "$COMMENT_EVENTS_CHECK" "${BLUEALBUM_HOME_BFF_ENABLED:-false}" "$RPC_LOAD_CHECK" <<'PY'
import json
import os
from pathlib import Path
import subprocess
import sys

output, project, *settings = sys.argv[1:]
revision = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
clean = not subprocess.check_output(["git", "status", "--porcelain"], text=True).strip()
expected = os.environ.get("GITHUB_SHA")
if expected and (expected != revision or not clean):
    raise SystemExit("This Compose gate needs a clean checkout of its configured GitHub SHA.")
keys = ("fullRegression", "splitChat", "cutover", "dualRealtime", "backup",
        "authorizationTiming", "grpc", "telemetry", "commentEvents", "homeBff", "rpcLoad")
Path(output).write_text(json.dumps({
    "commit": revision, "cleanWorktree": clean, "project": project,
    "configuration": {key: value in ("1", "true") for key, value in zip(keys, settings, strict=True)},
}, indent=2) + "\n", encoding="utf-8")
PY
[[ ! -e "$ENV_FILE" ]] || die "temporary credential file already exists: $ENV_FILE"
chat_cutover_admin=''
if [[ "$CUTOVER_MODE" == "1" || "$CHAT_REALTIME_CHECK" == "1" || "$CHAT_BACKUP_CHECK" == "1" ]]; then
  [[ ! -e "$CHAT_CUTOVER_STATE" && ! -e "${CHAT_CUTOVER_STATE%.json}.tmp" ]] \
    || die "temporary cutover state already exists: $CHAT_CUTOVER_STATE"
  CUTOVER_STATE_OWNED=1
fi
if [[ "$CUTOVER_MODE" == "1" || "$CHAT_BACKUP_CHECK" == "1" ]]; then
  chat_cutover_admin='chat-cutover-admin@example.test'
fi

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
if [[ "$TELEMETRY_CHECK" == 1 ]]; then
  [[ -n "${BLUEALBUM_OTEL_AGENT_JAR:-}" && -f "$BLUEALBUM_OTEL_AGENT_JAR" ]] \
    || die 'BLUEALBUM_OTEL_AGENT_JAR must point to an existing Java agent'
  telemetry_tempo_port="${BLUEALBUM_TEMPO_PORT:-3200}"
  telemetry_loki_port="${BLUEALBUM_LOKI_PORT:-3100}"
  export BLUEALBUM_TEMPO_PORT="$telemetry_tempo_port" BLUEALBUM_LOKI_PORT="$telemetry_loki_port"
  port_names+=(tempo loki)
  port_values+=("$telemetry_tempo_port" "$telemetry_loki_port")
fi
for index in "${!port_values[@]}"; do
  port="${port_values[$index]}"
  validate_port "$port"
  if port_is_occupied "$port"; then
    die "Compose host port is occupied (${port_names[$index]}:$port); use a clean CI runner or set COMPOSE_*_PORT"
  fi
done

db_password="$(random_hex_32)"
db_root_password="$(random_hex_32)"
notification_db_password="$(random_hex_32)"
notification_db_migration_password="$(random_hex_32)"
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
NOTIFICATION_DB_USER=bluealbum_ci_notify
NOTIFICATION_DB_PASSWORD=$notification_db_password
NOTIFICATION_DB_MIGRATION_USER=bluealbum_ci_notify_ddl
NOTIFICATION_DB_MIGRATION_PASSWORD=$notification_db_migration_password
REDIS_PASSWORD=$redis_password
RABBITMQ_USER=bluealbum_ci
RABBITMQ_PASSWORD=$rabbitmq_password
JWT_ISSUER=MagicAlbum
JWT_SECRET=$jwt_secret
APP_ADMIN_EMAIL=
APP_ADMIN_USER_ID=0
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
if [[ "$SPLIT_CHAT" == "1" ]]; then
  cat >>"$ENV_FILE" <<EOF
CHAT_DB_NAME=bluealbum_ci_chat
CHAT_DB_USER=bluealbum_ci_chat_app
CHAT_DB_PASSWORD=$(random_hex_32)
CHAT_DB_MIGRATION_USER=bluealbum_ci_chat_ddl
CHAT_DB_MIGRATION_PASSWORD=$(random_hex_32)
CHAT_DB_ROOT_PASSWORD=$(random_hex_32)
CHAT_INTERNAL_SERVICE_TOKEN=$(random_hex_32)
CHAT_GOVERNANCE_INTERNAL_TOKEN=$(random_hex_32)
CHAT_DB_URL=jdbc:mysql://chat-mysql:3306/bluealbum_ci_chat?useSSL=false&serverTimezone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&characterEncoding=utf8
CHAT_CORE_URL=http://backend:8080
EOF
fi
if [[ "$CHAT_REALTIME_CHECK" == "1" ]]; then
  printf 'CHAT_REALTIME_ALLOWED_ORIGINS=http://127.0.0.1:%s\n' "$frontend_port" >> "$ENV_FILE"
fi
if [[ "$CHAT_GRPC_CHECK" == 1 ]]; then
  GRPC_FIXTURE_DIR="$(mktemp -d)"
  MSYS2_ARG_CONV_EXCL="/CN=" openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=backend \
    -addext subjectAltName=DNS:backend -keyout "$GRPC_FIXTURE_DIR/tls.key" \
    -out "$GRPC_FIXTURE_DIR/tls.crt" >/dev/null 2>&1
  chmod 644 "$GRPC_FIXTURE_DIR/tls.crt"
  grpc_mount_dir="$GRPC_FIXTURE_DIR"
  if command -v cygpath >/dev/null 2>&1; then grpc_mount_dir="$(cygpath -am "$GRPC_FIXTURE_DIR")"; fi
  cat >>"$ENV_FILE" <<EOF
CHAT_REALTIME_GRPC_CREDENTIAL=$(random_hex_32)
CHAT_REALTIME_GRPC_CERTIFICATE="$grpc_mount_dir/tls.crt"
CHAT_REALTIME_GRPC_PRIVATE_KEY="$grpc_mount_dir/tls.key"
CHAT_REALTIME_GRPC_CA="$grpc_mount_dir/tls.crt"
EOF
fi
chmod 600 "$ENV_FILE"
# Compose prioritizes inherited shell values over --env-file, including empty values.
while IFS='=' read -r key _; do
  unset "$key"
done < "$ENV_FILE"

cat >"$OVERRIDE_FILE" <<'EOF'
services:
  rabbitmq:
    environment:
      RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS: "+S 2:2"
    healthcheck:
      test: ["CMD", "bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/5672"]
      start_period: 60s
      interval: 5s
      retries: 24
  backend:
    environment:
      JAVA_OPTS: "-Xmx384m -XX:ActiveProcessorCount=2"
EOF
if [[ "$FULL_MODE" == "1" ]]; then
  cat >>"$OVERRIDE_FILE" <<'EOF'
      AI_PROVIDER: "mock"
      AI_API_KEY: ""
      AI_BASE_URL: "http://127.0.0.1:9/disabled"
      AI_MODEL: "mock-summary"
      APP_AUTH_VERIFY_MODE: "off"
EOF
fi
cat >>"$OVERRIDE_FILE" <<'EOF'
  notification-service:
    environment:
      JAVA_OPTS: "-Xmx256m -XX:ActiveProcessorCount=2"
  gateway:
    environment:
      JAVA_OPTS: "-Xmx256m -XX:ActiveProcessorCount=2"
EOF
if [[ "$SPLIT_CHAT" == "1" ]]; then
  cat >>"$OVERRIDE_FILE" <<'EOF'
  chat-service:
    environment:
      JAVA_OPTS: "-Xmx256m -XX:ActiveProcessorCount=2"
EOF
fi
scale_options=()
if [[ "$CHAT_REALTIME_CHECK" == 1 || "$TELEMETRY_CHECK" == 1 ]]; then
  cat >>"$OVERRIDE_FILE" <<'EOF'
  chat-realtime:
    ports:
      - "127.0.0.1::8092"
EOF
fi
if [[ "$CHAT_REALTIME_CHECK" == 1 ]]; then
  scale_options=(--scale chat-realtime=2)
fi
if [[ "$CHAT_BACKUP_CHECK" == "1" ]]; then
  backup_pin_images
fi

COMPOSE_TOUCHED=1
compose config --quiet
services=(backend notification-service chat-realtime gateway frontend)
if [[ "$SPLIT_CHAT" == "1" ]]; then
  services+=(chat-service)
fi
for service in "${services[@]}"; do
  compose build "$service"
done
compose up -d --wait --wait-timeout 300 "${scale_options[@]}"
if [[ "$TELEMETRY_CHECK" == 1 ]]; then
  chat_transport=http
  if [[ "$CHAT_GRPC_CHECK" == 1 ]]; then chat_transport=grpc; fi
  realtime_probe="$(compose port --index 1 chat-realtime 8092)"
  realtime_probe="${realtime_probe//$'\r'/}"
  [[ "$realtime_probe" =~ ^127\.0\.0\.1:([0-9]+)$ ]] || die 'traced Go listener must be on loopback'
  telemetry_args=(--realtime "http://$realtime_probe")
  if [[ "${BLUEALBUM_HOME_BFF_ENABLED:-false}" == true ]]; then telemetry_args+=(--home-bff); fi
  "$PYTHON_BIN" "$SCRIPT_DIR/telemetry-runtime.py" \
    --gateway "http://127.0.0.1:$gateway_port" \
    --tempo "http://127.0.0.1:$telemetry_tempo_port" \
    --loki "http://127.0.0.1:$telemetry_loki_port" \
    --frontend "http://127.0.0.1:$frontend_port" --chat-transport "$chat_transport" \
    --output "$ARTIFACT_DIR/telemetry-runtime.json" "${telemetry_args[@]}"
fi

if [[ "$RPC_LOAD_CHECK" == 1 ]]; then
  # Separate temporary listeners let both transports reach the same Java Core and RabbitMQ.
  rpc_run=(run --detach --no-deps --publish '127.0.0.1::8092' --env OTEL_TRACES_EXPORTER=none)
  http_node="$(compose "${rpc_run[@]}" --name "$PROJECT-rpc-load-http" \
    --env CHAT_REALTIME_GRPC_ADDR= --env CHAT_REALTIME_GRPC_CREDENTIAL= chat-realtime)"
  grpc_node="$(compose "${rpc_run[@]}" --name "$PROJECT-rpc-load-grpc" chat-realtime)"
  "$PYTHON_BIN" "$SCRIPT_DIR/chat-auth-load.py" \
    --gateway "http://127.0.0.1:$gateway_port" --frontend "http://127.0.0.1:$frontend_port" \
    --project "$PROJECT" --http-container "${http_node//$'\r'/}" --grpc-container "${grpc_node//$'\r'/}" \
    --output "$ARTIFACT_DIR/chat-auth-load.json"
  if [[ "$TELEMETRY_CHECK" == 1 ]]; then verify_split_chat_owner 1; fi
  exit 0
fi

if [[ "$CHAT_AUTH_TIMING_CHECK" == "1" ]]; then
  chat_mysql_id=$(compose ps --quiet chat-mysql)
  "$PYTHON_BIN" "$SCRIPT_DIR/chat-auth-timing.py" \
    --api-url "http://127.0.0.1:$gateway_port/api/v1" \
    --project "$PROJECT" --mysql-container "${chat_mysql_id//$'\r'/}" \
    --output "$ARTIFACT_DIR/chat-auth-timing-checks.json"
  verify_split_chat_owner 3
  verify_chat_permissions
  exit 0
elif [[ "$CHAT_BACKUP_CHECK" == "1" ]]; then
  run_chat_backup
  printf 'Split-chat backup and recovery passed for %s\n' "$PROJECT"
  exit 0
elif [[ "$CHAT_REALTIME_CHECK" == "1" ]]; then
  run_chat_realtime_check
  verify_split_chat_owner 6
  printf 'Dual chat realtime with the split chat writer passed for %s\n' "$PROJECT"
  exit 0
elif [[ "$CUTOVER_MODE" == "1" ]]; then
  run_chat_cutover
  printf 'Chat application cutover and rollback passed for %s\n' "$PROJECT"
  exit 0
fi

export TEST_BASE_URL="http://127.0.0.1:$frontend_port"
export TEST_API_BASE_URL="http://127.0.0.1:$gateway_port/api/v1"
export TEST_PLAYWRIGHT_CHANNEL=""
export TEST_ARTIFACT_DIR="$SCREENSHOT_DIR"
if [[ "$FULL_MODE" == "1" ]]; then
  export TEST_LAYOUT_ARTIFACTS="$SCREENSHOT_DIR"
fi
export TEST_EMAIL=''
export TEST_PHONE=''
export TEST_PASSWORD=''

write_env_value() {
  local key="$1"
  local value="$2"
  "$PYTHON_BIN" - "$ENV_FILE" "$key" "$value" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
key = sys.argv[2]
value = sys.argv[3]
lines = path.read_text(encoding="utf-8").splitlines()
updated = False
result = []
for line in lines:
    if line.startswith(key + "="):
        result.append(f"{key}={value}")
        updated = True
    else:
        result.append(line)
if not updated:
    result.append(f"{key}={value}")
path.write_text("\n".join(result) + "\n", encoding="utf-8", newline="\n")
PY
}

wait_for_gateway_api() {
  local deadline=$((SECONDS + 120))
  local status
  while (( SECONDS < deadline )); do
    status="$("$PYTHON_BIN" - "$TEST_API_BASE_URL" <<'PY'
import sys
from urllib.request import Request, urlopen

base_url = sys.argv[1].rstrip("/")
try:
    with urlopen(
        Request(
            f"{base_url}/sections?page=1&size=1",
            headers={"Accept": "application/json"},
            method="GET",
        ),
        timeout=5,
    ) as response:
        print(response.status)
except Exception:
    # Readiness output is deliberately limited to an HTTP status.  Gateway
    # response bodies may contain request data and must never enter CI logs.
    print(0)
PY
    )"
    if [[ "$status" == "200" ]]; then
      return 0
    fi
    sleep 2
  done
  die 'gateway did not expose the sections API after backend recreation'
}

if [[ "$FULL_MODE" == "1" ]]; then
  [[ ! -e "$FULL_CREDENTIALS_FILE" ]] || die "temporary full-regression credentials file already exists: $FULL_CREDENTIALS_FILE"
  FULL_CREDENTIALS_CREATED=1
  "$PYTHON_BIN" scripts/ci/prepare-python-full-regression.py \
    --base-url "$TEST_API_BASE_URL" \
    --credentials-file "$FULL_CREDENTIALS_FILE" \
    --bootstrap

  # The generated file contains only CI-local values and is removed by the
  # cleanup trap.  It is sourced after generation so no repository .env file
  # can influence the full run.
  set -a
  . "$FULL_CREDENTIALS_FILE"
  set +a
  write_env_value APP_ADMIN_EMAIL "$APP_ADMIN_EMAIL"
  write_env_value APP_ADMIN_USER_ID "$APP_ADMIN_USER_ID"
  compose up -d --no-deps --force-recreate --wait --wait-timeout 180 backend
  # Backend recreation can leave the gateway briefly holding an old upstream
  # connection.  Probe only a read-only public API through the gateway before
  # performing the one login and the seed writes below.
  wait_for_gateway_api

  "$PYTHON_BIN" scripts/ci/prepare-python-full-regression.py \
    --base-url "$TEST_API_BASE_URL" \
    --credentials-file "$FULL_CREDENTIALS_FILE" \
    --verify-admin-and-seed \
    --evidence-file "$FULL_EVIDENCE_FILE" \
    --seed-count 11

  "$PYTHON_BIN" -m pytest -c pytest.ini \
    --collect-only -q -p no:cacheprovider tests >"$FULL_COLLECT_FILE"
  "$PYTHON_BIN" - "$FULL_COLLECT_FILE" 148 <<'PY'
import re
import sys
from pathlib import Path

text = Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
expected = int(sys.argv[2])
matches = re.findall(r"collected\s+(\d+)\s+(?:items?|tests?)", text, flags=re.IGNORECASE)
if not matches:
    matches = re.findall(r"(\d+)\s+(?:items?|tests?)\s+collected", text, flags=re.IGNORECASE)
if not matches or int(matches[-1]) != expected:
    raise SystemExit(f"full Python collection must be exactly {expected}; output did not prove that count")
print(f"Full Python collection passed: {expected} tests")
PY

  set +e
  "$PYTHON_BIN" -m pytest -c pytest.ini tests -q -p no:cacheprovider \
    --maxfail=0 -n0 --junitxml "$FULL_JUNIT_FILE"
  full_status=$?
  set -e

  [[ -f "$FULL_JUNIT_FILE" ]] || die "full Python pytest did not produce a JUnit report: $FULL_JUNIT_FILE"
  validate_junit "$FULL_JUNIT_FILE" 148 '' 31
  (( full_status == 0 )) || die "full Python regression exited with status $full_status"
  verify_split_chat_owner
  cp -- "$FULL_JUNIT_FILE" "$JUNIT_FILE"
  if [[ "$CHAT_GRPC_CHECK" == 1 ]]; then
    printf 'grpc_tls_websocket_journey=passed\n' > "$ARTIFACT_DIR/chat-grpc-checks.txt"
  fi
  if [[ "$COMMENT_EVENTS_CHECK" == 1 ]]; then
    comment_queue=$(compose exec -T rabbitmq rabbitmqctl --quiet list_queues name consumers messages |
      awk '$1 == "notification.comment-created.v1" {print $2 ":" $3}')
    comment_queue="${comment_queue//$'\r'/}"
    [[ "$comment_queue" == "1:0" ]] || die 'Comment notification queue did not drain to its dedicated consumer'
    printf 'comment_notification_browser=passed\ndedicated_queue=%s\n' "$comment_queue" \
      > "$ARTIFACT_DIR/comment-events-checks.txt"
  fi
  printf 'Full Python regression passed for %s: 148 tests, zero skips\n' "$PROJECT"
  exit 0
fi

set +e
"$PYTHON_BIN" -m pytest -c pytest.ini \
  tests/api/test_ci_contract.py \
  tests/api/test_bookmarks.py \
  tests/api/test_comments.py \
  tests/api/test_threads.py \
  tests/api/test_user.py \
  -q --junitxml "$API_JUNIT_FILE"
api_status=$?
set -e

[[ -f "$API_JUNIT_FILE" ]] || die "API pytest did not produce a JUnit report: $API_JUNIT_FILE"
validate_junit "$API_JUNIT_FILE" 14
(( api_status == 0 )) || die "CI API contract suite exited with status $api_status"

set +e
"$PYTHON_BIN" -m pytest -c pytest.ini tests/e2e/selenium/test_ci_smoke.py -q \
  --selenium-browser=chrome --junitxml "$SELENIUM_JUNIT_FILE"
selenium_status=$?
set -e

[[ -f "$SELENIUM_JUNIT_FILE" ]] || die "Selenium pytest did not produce a JUnit report: $SELENIUM_JUNIT_FILE"
validate_junit "$SELENIUM_JUNIT_FILE" 1
(( selenium_status == 0 )) || die "CI Selenium smoke exited with status $selenium_status"

# Populate only the disposable M0 database, after the API and Selenium cases.
compose exec -T mysql sh -c '
  MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --batch -u "$MYSQL_USER" "$MYSQL_DATABASE"
' <<'SQL'
INSERT INTO sections (name, slug)
WITH RECURSIVE sequence_numbers (n) AS (
    SELECT 1
    UNION ALL SELECT n + 1 FROM sequence_numbers WHERE n < 101
)
SELECT CONCAT('CI editor section ', n), CONCAT('ci-editor-section-', n)
FROM sequence_numbers;
SQL
export TEST_M0_PAGED_SECTIONS=1

set +e
"$PYTHON_BIN" -m pytest -c pytest.ini tests/e2e/playwright/test_m0_journey.py -q --junitxml "$UI_JUNIT_FILE"
ui_status=$?
set -e

[[ -f "$UI_JUNIT_FILE" ]] || die "browser pytest did not produce a JUnit report: $UI_JUNIT_FILE"
validate_junit "$UI_JUNIT_FILE" 1 2 31
(( ui_status == 0 )) || die "M0 browser journey exited with status $ui_status"

verify_split_chat_owner

# Keep the historical artifact name for local tooling that already consumes it.
cp -- "$UI_JUNIT_FILE" "$JUNIT_FILE"

printf 'M0 browser Compose smoke passed for %s\n' "$PROJECT"
