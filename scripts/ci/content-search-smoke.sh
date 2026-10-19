#!/usr/bin/env bash

set -Eeuo pipefail
IFS=$'\n\t'
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
ARTIFACT_ROOT="$ROOT_DIR/outputs/m2-search-ci"

RUN_ID=""
MYSQL_NAME=""
RABBIT_NAME=""
ES_NAME=""
MYSQL_PORT=""
RABBIT_PORT=""
ES_PORT=""
ES_URL=""
SEARCH_DATABASE=""
MYSQL_ROOT_PASSWORD=""
RABBIT_USERNAME=""
RABBIT_PASSWORD=""
MYSQL_ENV_FILE=""
RABBIT_ENV_FILE=""
TEST_ENV_FILE=""
MAVEN_COMMAND="${MAVEN_EXECUTABLE:-mvn}"
REPORT_CLASS="com.example.demo.search.ContentSearchRuntimeAcceptance"
REPORT_XML="TEST-${REPORT_CLASS}.xml"
REPORT_TEXT="${REPORT_CLASS}.txt"
FAILURE_MESSAGE=""
RUNTIME_SUCCEEDED=false
CLEANUP_SUCCEEDED=false
CREDENTIAL_FILE_REMAINING=false
CONTAINERS_CREATED=false

declare -A CONTAINER_ID=()
declare -A CONTAINER_VOLUMES=()
declare -A CONTAINER_STARTED=()
declare -A CONTAINER_NAME=()
declare -A CONTAINER_INITIAL_PORT=()
declare -A CONTAINER_CURRENT_PORT=()
declare -A CONTAINER_PORT_HISTORY=()
declare -A ROUND_EXIT=()
declare -A ROUND_TESTS=()
declare -A ROUND_FAILURES=()
declare -A ROUND_ERRORS=()
declare -A ROUND_SKIPPED=()

for role in mysql rabbit es; do
  CONTAINER_ID[$role]=""
  CONTAINER_VOLUMES[$role]=""
  CONTAINER_STARTED[$role]=0
  CONTAINER_INITIAL_PORT[$role]=""
  CONTAINER_CURRENT_PORT[$role]=""
  CONTAINER_PORT_HISTORY[$role]=""
done

die() {
  FAILURE_MESSAGE="$*"
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  local command_name="$1"
  command -v "$command_name" >/dev/null 2>&1 \
    || die "required command is unavailable: $command_name"
}

random_hex() {
  local byte_count="$1"
  local expected_length=$((byte_count * 2))
  local value
  value="$(openssl rand -hex "$byte_count" 2>/dev/null)" \
    || die 'failed to generate an isolated random value'
  value="${value//$'\r'/}"
  value="${value//$'\n'/}"
  [[ "$value" =~ ^[0-9a-f]+$ ]] || die 'random generator returned an invalid value'
  [[ "${#value}" -eq "$expected_length" ]] || die 'random generator returned an invalid length'
  printf '%s' "$value"
}

validate_port() {
  local port="$1"
  [[ "$port" =~ ^[0-9]+$ ]] || die "invalid dynamic port: $port"
  ((port >= 1 && port <= 65535)) || die "dynamic port is outside 1..65535: $port"
}

assert_artifact_root_is_empty() {
  mkdir -p -- "$ARTIFACT_ROOT" || die "cannot create artifact root: $ARTIFACT_ROOT"
  local entry
  entry="$(find "$ARTIFACT_ROOT" -mindepth 1 -maxdepth 1 -print -quit)"
  [[ -z "$entry" ]] || die "artifact root must be empty before a run: $ARTIFACT_ROOT"
}

write_env_file() {
  local path="$1"
  shift
  : >"$path" || die "cannot create temporary environment file: $path"
  chmod 600 "$path" || die "cannot restrict temporary environment file: $path"
  while (($# > 0)); do
    printf '%s\n' "$1" >>"$path" || die "cannot write temporary environment file: $path"
    shift
  done
}

container_port() {
  local name="$1"
  local target="$2"
  local published
  published="$(docker port "$name" "$target" 2>/dev/null)" \
    || die "cannot inspect published port for $name"
  [[ "$published" =~ ^127\.0\.0\.1:([0-9]+)$ ]] \
    || die "container $name is not published on a loopback-only dynamic port"
  local port="${BASH_REMATCH[1]}"
  validate_port "$port"
  printf '%s' "$port"
}

run_container() {
  local role="$1"
  local name="$2"
  shift 2
  local id
  id="$(docker run -d --name "$name" --label "com.bluealbum.m2-search=$RUN_ID" "$@")" \
    || die "failed to start owned $role container"
  id="${id//$'\r'/}"
  id="${id//$'\n'/}"
  [[ "$id" =~ ^[0-9a-f]{12,64}$ ]] || die "Docker returned an invalid $role container id"
  CONTAINER_ID[$role]="$id"
  CONTAINER_NAME[$role]="$name"
  CONTAINER_STARTED[$role]=1
  CONTAINERS_CREATED=true
  record_container_volumes "$role" "$name" \
    || die "cannot record owned $role anonymous volumes"
}

container_volume_names() {
  local mounts_json="$1"
  python - "$mounts_json" <<'PY'
import json
import sys

mounts = json.loads(sys.argv[1])
if not isinstance(mounts, list) or not mounts:
    raise SystemExit("container has no anonymous data volume")
names = []
for mount in mounts:
    if not isinstance(mount, dict) or mount.get("Type") != "volume":
        raise SystemExit("container has a non-anonymous or host bind mount")
    name = mount.get("Name")
    if not isinstance(name, str) or not name:
        raise SystemExit("container has an unnamed volume")
    names.append(name)
names.sort()
print(" ".join(names))
PY
}

record_container_volumes() {
  local role="$1"
  local name="$2"
  local mounts_json volumes
  mounts_json="$(docker inspect --format '{{json .Mounts}}' "$name" 2>/dev/null)" \
    || return 1
  volumes="$(container_volume_names "$mounts_json")" || return 1
  CONTAINER_VOLUMES[$role]="$volumes"
}

record_container_port() {
  local role="$1"
  local port="$2"
  validate_port "$port" || return 1
  if [[ -z "${CONTAINER_CURRENT_PORT[$role]}" ]]; then
    CONTAINER_INITIAL_PORT[$role]="$port"
    CONTAINER_PORT_HISTORY[$role]="$port"
  else
    CONTAINER_PORT_HISTORY[$role]+=" $port"
  fi
  CONTAINER_CURRENT_PORT[$role]="$port"
}

capture_container_metadata() {
  local role="$1"
  local name="$2"
  local port="$3"
  local expected_memory_mib="$4"
  local id label image memory memory_swap mounts_json
  id="$(docker inspect --format '{{.Id}}' "$name")" \
    || die "cannot inspect owned $role container id"
  label="$(docker inspect --format '{{ index .Config.Labels "com.bluealbum.m2-search" }}' "$name")" \
    || die "cannot inspect owned $role run label"
  [[ "$id" == "${CONTAINER_ID[$role]}" ]] || die "owned $role container id changed"
  [[ "$label" == "$RUN_ID" ]] || die "owned $role container run label changed"

  image="$(docker inspect --format '{{.Config.Image}}' "$name")" \
    || die "cannot inspect $role image"
  memory="$(docker inspect --format '{{.HostConfig.Memory}}' "$name")" \
    || die "cannot inspect $role memory limit"
  memory_swap="$(docker inspect --format '{{.HostConfig.MemorySwap}}' "$name")" \
    || die "cannot inspect $role memory-swap limit"
  [[ "$memory" =~ ^[0-9]+$ ]] || die "invalid $role memory limit"
  [[ "$memory_swap" =~ ^[0-9]+$ ]] || die "invalid $role memory-swap limit"
  [[ "$memory" -eq $((expected_memory_mib * 1024 * 1024)) ]] \
    || die "$role memory limit is not ${expected_memory_mib}MiB"
  [[ "$memory_swap" -eq $((expected_memory_mib * 1024 * 1024)) ]] \
    || die "$role memory-swap limit is not ${expected_memory_mib}MiB"

  mounts_json="$(docker inspect --format '{{json .Mounts}}' "$name")" \
    || die "cannot inspect $role mounts"
  [[ -n "${CONTAINER_VOLUMES[$role]}" ]] \
    || die "$role container does not have recorded anonymous volumes"
  record_container_port "$role" "$port" \
    || die "cannot record owned $role loopback port"

  python - "$ARTIFACT_ROOT/container-record.json" "$role" "$name" "$id" "$label" "$image" "$port" "$memory" "$memory_swap" "$mounts_json" \
    "${CONTAINER_INITIAL_PORT[$role]}" "${CONTAINER_CURRENT_PORT[$role]}" "${CONTAINER_PORT_HISTORY[$role]}" <<'PY'
import json
import pathlib
import sys

(
    path, role, name, identifier, label, image, port, memory, memory_swap,
    mounts_text, initial_port, current_port, history_text,
) = sys.argv[1:]
mounts = json.loads(mounts_text)
safe_mounts = [
    {"type": item.get("Type"), "name": item.get("Name"), "destination": item.get("Destination")}
    for item in mounts
]
history = [int(value) for value in history_text.split()]
if not history or history[0] != int(initial_port) or history[-1] != int(current_port):
    raise SystemExit("container port history is inconsistent")
target = pathlib.Path(path)
if target.exists():
    payload = json.loads(target.read_text(encoding="utf-8"))
else:
    payload = {"runId": label, "containers": {}}
payload.setdefault("containers", {})[role] = {
    "name": name,
    "id": identifier,
    "label": label,
    "image": image,
    "loopbackPort": int(current_port),
    "initialLoopbackPort": int(initial_port),
    "currentLoopbackPort": int(current_port),
    "portHistory": history,
    "memoryBytes": int(memory),
    "memorySwapBytes": int(memory_swap),
    "anonymousVolumes": safe_mounts,
}
target.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
}

assert_owned_container() {
  local role="$1"
  local name="${CONTAINER_NAME[$role]}"
  local expected_id="${CONTAINER_ID[$role]}"
  local id label mounts_json volumes
  id="$(docker inspect --format '{{.Id}}' "$name" 2>/dev/null)" \
    || die "cannot inspect owned $role container id"
  label="$(docker inspect --format '{{ index .Config.Labels "com.bluealbum.m2-search" }}' "$name" 2>/dev/null)" \
    || die "cannot inspect owned $role run label"
  [[ "$id" == "$expected_id" ]] || die "owned $role container id changed"
  [[ "$label" == "$RUN_ID" ]] || die "owned $role container run label changed"
  mounts_json="$(docker inspect --format '{{json .Mounts}}' "$name" 2>/dev/null)" \
    || die "cannot inspect owned $role mounts"
  volumes="$(container_volume_names "$mounts_json")" \
    || die "owned $role container volumes are invalid"
  [[ "$volumes" == "${CONTAINER_VOLUMES[$role]}" ]] \
    || die "owned $role container volumes changed"
}

update_container_port_metadata() {
  local role="$1"
  local port="$2"
  python - "$ARTIFACT_ROOT/container-record.json" "$role" "$port" \
    "${CONTAINER_ID[$role]}" "$RUN_ID" "${CONTAINER_VOLUMES[$role]}" \
    "${CONTAINER_INITIAL_PORT[$role]}" "${CONTAINER_CURRENT_PORT[$role]}" \
    "${CONTAINER_PORT_HISTORY[$role]}" <<'PY'
import json
import pathlib
import sys

(
    path, role, port, expected_id, expected_label, expected_volumes,
    initial_port, current_port, history_text,
) = sys.argv[1:]
target = pathlib.Path(path)
payload = json.loads(target.read_text(encoding="utf-8"))
container = payload.get("containers", {}).get(role)
if not isinstance(container, dict):
    raise SystemExit(f"container metadata is missing role: {role}")
if container.get("id") != expected_id or container.get("label") != expected_label:
    raise SystemExit(f"container identity changed for role: {role}")
stored_volumes = sorted(
    item.get("name")
    for item in container.get("anonymousVolumes", [])
    if isinstance(item, dict) and item.get("name")
)
if stored_volumes != sorted(filter(None, expected_volumes.split())):
    raise SystemExit(f"container volumes changed for role: {role}")
history = [int(value) for value in history_text.split()]
initial = int(initial_port)
current = int(current_port)
if not history or history[0] != initial or history[-1] != current or current != int(port):
    raise SystemExit(f"container port history is inconsistent for role: {role}")
stored_initial = container.get("initialLoopbackPort", container.get("loopbackPort"))
if int(stored_initial) != initial:
    raise SystemExit(f"container initial port changed for role: {role}")
container["loopbackPort"] = current
container["currentLoopbackPort"] = current
container["portHistory"] = history
target.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
}

refresh_container_port() {
  local role="$1"
  local target port
  case "$role" in
    mysql) target='3306/tcp' ;;
    rabbit) target='5672/tcp' ;;
    es) target='9200/tcp' ;;
    *) die "cannot refresh port for unknown container role: $role" ;;
  esac
  port="$(container_port "${CONTAINER_NAME[$role]}" "$target")" \
    || die "cannot refresh published port for $role container"
  record_container_port "$role" "$port" \
    || die "cannot record refreshed $role loopback port"
  case "$role" in
    mysql)
      MYSQL_PORT="$port"
      export CONTENT_SEARCH_TEST_DATASOURCE_URL="jdbc:mysql://127.0.0.1:$MYSQL_PORT/$SEARCH_DATABASE?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8"
      ;;
    rabbit)
      RABBIT_PORT="$port"
      export CONTENT_SEARCH_TEST_RABBIT_PORT="$RABBIT_PORT"
      ;;
    es)
      ES_PORT="$port"
      ES_URL="http://127.0.0.1:$ES_PORT"
      export CONTENT_SEARCH_TEST_ELASTIC_URL="$ES_URL"
      ;;
  esac
  update_container_port_metadata "$role" "$port" \
    || die "cannot update $role port metadata"
}

mysql_query() {
  local query="$1"
  docker exec "${CONTAINER_NAME[mysql]}" sh -c \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --protocol=TCP -h 127.0.0.1 -u root --batch --raw --skip-column-names -Nse "$1"' \
    sh "$query" 2>/dev/null
}

wait_for_mysql() {
  local deadline=$((SECONDS + 180))
  while ((SECONDS < deadline)); do
    if [[ "$(mysql_query 'SELECT 1;' || true)" == "1" ]]; then
      return 0
    fi
    sleep 2
  done
  return 1
}

wait_for_rabbit() {
  local deadline=$((SECONDS + 180))
  while ((SECONDS < deadline)); do
    if docker exec "${CONTAINER_NAME[rabbit]}" rabbitmq-diagnostics -q ping >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

es_probe() {
  local root_body health_body
  root_body="$(curl --fail --silent --show-error --max-time 5 "$ES_URL" 2>/dev/null)" \
    || return 1
  health_body="$(curl --fail --silent --show-error --max-time 5 \
    "$ES_URL/_cluster/health?wait_for_status=yellow&timeout=3s" 2>/dev/null)" \
    || return 1
  python - "$root_body" "$health_body" <<'PY'
import json
import sys

root = json.loads(sys.argv[1])
health = json.loads(sys.argv[2])
version = root.get("version", {}).get("number")
status = health.get("status")
nodes = health.get("number_of_nodes")
if version != "8.17.10":
    raise SystemExit("Elasticsearch did not report version 8.17.10")
if status not in {"green", "yellow"} or not isinstance(nodes, int) or nodes < 1:
    raise SystemExit("Elasticsearch cluster health is not ready")
print(version)
PY
}

wait_for_elasticsearch() {
  local deadline=$((SECONDS + 240))
  local version
  while ((SECONDS < deadline)); do
    if version="$(es_probe 2>/dev/null)"; then
      printf '%s' "$version"
      return 0
    fi
    sleep 3
  done
  return 1
}

rabbit_version() {
  local version
  version="$(docker exec "${CONTAINER_NAME[rabbit]}" rabbitmq-diagnostics -q server_version 2>/dev/null)" \
    || version="$(docker exec "${CONTAINER_NAME[rabbit]}" rabbitmqctl version 2>/dev/null)" \
    || return 1
  version="${version//$'\r'/}"
  version="${version//$'\n'/}"
  [[ "$version" =~ ^3\.13\.[0-9]+$ ]] || return 1
  printf '%s' "$version"
}

mysql_version() {
  local version
  version="$(mysql_query 'SELECT VERSION();')" || return 1
  [[ "$version" =~ ^8\.[0-9]+\.[0-9]+ ]] || return 1
  printf '%s' "$version"
}

record_versions() {
  local mysql_version_value rabbit_version_value es_version_value
  mysql_version_value="$(mysql_version)" || die 'MySQL did not report an 8.x version'
  rabbit_version_value="$(rabbit_version)" || die 'RabbitMQ did not report a 3.13.x version'
  es_version_value="$(es_probe)" || die 'Elasticsearch did not report a ready 8.x version'
  python - "$ARTIFACT_ROOT/container-record.json" "$mysql_version_value" "$rabbit_version_value" "$es_version_value" <<'PY'
import json
import pathlib
import sys

path, mysql_version, rabbit_version, es_version = sys.argv[1:]
payload = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
payload["versions"] = {
    "mysql": mysql_version,
    "rabbitmq": rabbit_version,
    "elasticsearch": es_version,
}
pathlib.Path(path).write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
}

wait_for_services() {
  wait_for_mysql || die 'MySQL did not become ready'
  wait_for_rabbit || die 'RabbitMQ did not become ready'
  wait_for_elasticsearch >/dev/null || die 'Elasticsearch did not become ready'
  record_versions
}

sanitize_file() {
  local source="$1"
  local destination="$2"
  MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-}" \
  RABBIT_USERNAME="${RABBIT_USERNAME:-}" \
  RABBIT_PASSWORD="${RABBIT_PASSWORD:-}" \
  CONTENT_SEARCH_TEST_DB_PASSWORD="${CONTENT_SEARCH_TEST_DB_PASSWORD:-}" \
  CONTENT_SEARCH_TEST_RABBIT_PASSWORD="${CONTENT_SEARCH_TEST_RABBIT_PASSWORD:-}" \
  python - "$source" "$destination" <<'PY'
import pathlib
import os
import sys

source, destination = sys.argv[1:]
secrets = [
    os.environ.get("MYSQL_ROOT_PASSWORD", ""),
    os.environ.get("RABBIT_USERNAME", ""),
    os.environ.get("RABBIT_PASSWORD", ""),
    os.environ.get("CONTENT_SEARCH_TEST_DB_PASSWORD", ""),
    os.environ.get("CONTENT_SEARCH_TEST_RABBIT_PASSWORD", ""),
]
text = pathlib.Path(source).read_text(encoding="utf-8", errors="replace")
for secret in secrets:
    if secret:
        text = text.replace(secret, "[REDACTED]")
pathlib.Path(destination).write_text(text, encoding="utf-8")
PY
}

sanitize_docker_logs() {
  local name="$1"
  local destination="$2"
  local raw_log=""
  local docker_status=0
  raw_log="$(mktemp "${TMPDIR:-/tmp}/bluealbum-m2-docker-log.XXXXXX")" \
    || return 1
  docker logs "$name" >"$raw_log" 2>&1 || docker_status=$?
  sanitize_file "$raw_log" "$destination" || docker_status=1
  rm -f -- "$raw_log" || docker_status=1
  return "$docker_status"
}

save_service_logs() {
  local log_directory="$ARTIFACT_ROOT/service-logs"
  local role status=0
  mkdir -p -- "$log_directory" || return 1
  for role in mysql rabbit es; do
    if [[ "${CONTAINER_STARTED[$role]}" == 1 ]]; then
      sanitize_docker_logs "${CONTAINER_NAME[$role]}" "$log_directory/${role}.log" || status=1
    fi
  done
  return "$status"
}

validate_junit_report() {
  local report="$1"
  local phase="$2"
  python - "$report" "$phase" <<'PY'
import pathlib
import sys
import xml.etree.ElementTree as ET

report, phase = sys.argv[1:]
root = ET.parse(report).getroot()
suites = list(root.iter("testsuite"))
cases = list(root.iter("testcase"))
if not suites:
    raise SystemExit(f"{phase}: report has no testsuite element")
if len(cases) != 1:
    raise SystemExit(f"{phase}: expected exactly one testcase, got {len(cases)}")

def integer_attribute(element, name):
    value = element.attrib.get(name)
    if value is None:
        raise SystemExit(f"{phase}: testsuite is missing {name} attribute")
    try:
        return int(value)
    except ValueError as exc:
        raise SystemExit(f"{phase}: testsuite {name} is not an integer") from exc

for suite in suites:
    if integer_attribute(suite, "tests") != 1:
        raise SystemExit(f"{phase}: testsuite tests attribute must be 1")
    for name in ("failures", "errors", "skipped"):
        if integer_attribute(suite, name) != 0:
            raise SystemExit(f"{phase}: testsuite {name} attribute must be 0")

failure_count = sum(1 for case in cases for child in case if child.tag in {"failure", "error"})
error_count = sum(1 for case in cases for child in case if child.tag == "error")
skipped_count = sum(1 for case in cases for child in case if child.tag == "skipped")
if failure_count != 0 or error_count != 0 or skipped_count != 0:
    raise SystemExit(f"{phase}: testcase contains failure/error/skip elements")
print(f"1 0 0 0")
PY
}

write_test_environment() {
  write_env_file "$TEST_ENV_FILE" \
    "CONTENT_SEARCH_TEST_DATASOURCE_URL=$CONTENT_SEARCH_TEST_DATASOURCE_URL" \
    "CONTENT_SEARCH_TEST_DATABASE=$CONTENT_SEARCH_TEST_DATABASE" \
    "CONTENT_SEARCH_TEST_DB_USERNAME=$CONTENT_SEARCH_TEST_DB_USERNAME" \
    "CONTENT_SEARCH_TEST_DB_PASSWORD=$CONTENT_SEARCH_TEST_DB_PASSWORD" \
    "CONTENT_SEARCH_TEST_RABBIT_PORT=$CONTENT_SEARCH_TEST_RABBIT_PORT" \
    "CONTENT_SEARCH_TEST_RABBIT_USERNAME=$CONTENT_SEARCH_TEST_RABBIT_USERNAME" \
    "CONTENT_SEARCH_TEST_RABBIT_PASSWORD=$CONTENT_SEARCH_TEST_RABBIT_PASSWORD" \
    "CONTENT_SEARCH_TEST_ELASTIC_URL=$CONTENT_SEARCH_TEST_ELASTIC_URL" \
    "CONTENT_SEARCH_TEST_EVIDENCE_DIRECTORY=$CONTENT_SEARCH_TEST_EVIDENCE_DIRECTORY" \
    "CONTENT_SEARCH_TEST_PHASE=$CONTENT_SEARCH_TEST_PHASE" \
    'SPRING_CONFIG_IMPORT='
}

run_maven_phase() {
  local phase="$1"
  local method="$2"
  local phase_directory="$ARTIFACT_ROOT/$phase"
  local reports_directory="$ROOT_DIR/backend/target/surefire-reports"
  local report_xml="$reports_directory/$REPORT_XML"
  local report_text="$reports_directory/$REPORT_TEXT"
  local raw_log=""
  local maven_exit=0
  local summary=""
  local validation_status=0
  local tests=0 failures=0 errors=0 skipped=0

  mkdir -p -- "$phase_directory" || die "cannot create evidence directory: $phase_directory"
  export CONTENT_SEARCH_TEST_PHASE="$phase"
  export CONTENT_SEARCH_TEST_EVIDENCE_DIRECTORY="$phase_directory"
  write_test_environment

  rm -f -- "$report_xml" "$report_text" \
    || die "cannot remove stale Surefire report files for $phase"
  [[ ! -e "$report_xml" && ! -e "$report_text" ]] \
    || die "stale Surefire report files remain for $phase"

  raw_log="$(mktemp "${TMPDIR:-/tmp}/bluealbum-m2-maven.XXXXXX")" \
    || die "cannot create temporary Maven log for $phase"
  set +e
  (
    cd -- "$ROOT_DIR" || exit 125
    "$MAVEN_COMMAND" -pl backend "-Dtest=${REPORT_CLASS}#${method}" \
      -Dsurefire.failIfNoSpecifiedTests=true test
  ) >"$raw_log" 2>&1
  maven_exit=$?
  set -e
  sanitize_file "$raw_log" "$phase_directory/maven.log" \
    || die "cannot sanitize Maven log for $phase"
  rm -f -- "$raw_log" || die "cannot remove temporary Maven log for $phase"
  printf '%s\n' "$maven_exit" >"$phase_directory/exit-code.txt" \
    || die "cannot write Maven exit code for $phase"

  if [[ ! -f "$report_xml" || ! -f "$report_text" ]]; then
    ROUND_EXIT[$phase]="$maven_exit"
    ROUND_TESTS[$phase]=0
    ROUND_FAILURES[$phase]=-1
    ROUND_ERRORS[$phase]=-1
    ROUND_SKIPPED[$phase]=-1
    FAILURE_MESSAGE="${phase}: expected fresh Surefire XML and TXT reports"
    return 1
  fi
  sanitize_file "$report_xml" "$phase_directory/$REPORT_XML" \
    || die "cannot copy XML report for $phase"
  sanitize_file "$report_text" "$phase_directory/$REPORT_TEXT" \
    || die "cannot copy text report for $phase"

  set +e
  summary="$(validate_junit_report "$report_xml" "$phase" 2>"$phase_directory/junit-validation-error.txt")"
  validation_status=$?
  set -e
  if [[ "$validation_status" -ne 0 ]]; then
    ROUND_EXIT[$phase]="$maven_exit"
    ROUND_TESTS[$phase]=0
    ROUND_FAILURES[$phase]=-1
    ROUND_ERRORS[$phase]=-1
    ROUND_SKIPPED[$phase]=-1
    FAILURE_MESSAGE="${phase}: JUnit report did not contain exactly one passing testcase"
    return 1
  fi
  IFS=' ' read -r tests failures errors skipped <<<"$summary"
  ROUND_EXIT[$phase]="$maven_exit"
  ROUND_TESTS[$phase]="$tests"
  ROUND_FAILURES[$phase]="$failures"
  ROUND_ERRORS[$phase]="$errors"
  ROUND_SKIPPED[$phase]="$skipped"
  printf 'tests=%s failures=%s errors=%s skipped=%s\n' \
    "$tests" "$failures" "$errors" "$skipped" >"$phase_directory/junit-summary.txt" \
    || die "cannot write JUnit summary for $phase"
  [[ "$maven_exit" -eq 0 ]] \
    || { FAILURE_MESSAGE="${phase}: Maven exited with status $maven_exit"; return 1; }
}

assert_stopped() {
  local role="$1"
  local name="${CONTAINER_NAME[$role]}"
  local state running
  state="$(docker inspect --format '{{.State.Status}}' "$name" 2>/dev/null)" \
    || die "$role container cannot be inspected after stop"
  running="$(docker inspect --format '{{.State.Running}}' "$name" 2>/dev/null)" \
    || die "$role container running state cannot be inspected after stop"
  [[ "$state" == "exited" && "$running" == "false" ]] \
    || die "$role container was not stopped"
}

stop_role() {
  local role="$1"
  assert_owned_container "$role"
  docker stop "${CONTAINER_NAME[$role]}" >/dev/null \
    || die "failed to stop $role container"
  assert_stopped "$role"
}

start_role() {
  local role="$1"
  assert_owned_container "$role"
  docker start "${CONTAINER_NAME[$role]}" >/dev/null \
    || die "failed to start $role container"
  local running
  running="$(docker inspect --format '{{.State.Running}}' "${CONTAINER_NAME[$role]}" 2>/dev/null)" \
    || die "$role container running state cannot be inspected after start"
  [[ "$running" == "true" ]] || die "$role container did not start"
  assert_owned_container "$role"
  refresh_container_port "$role"
}

write_checks() {
  local output="$ARTIFACT_ROOT/checks.json"
  local runtime_status="$1"
  local cleanup_status="$2"
  local credential_status="$3"
  local original_status="$4"
  python - "$output" "$RUN_ID" "$runtime_status" "$cleanup_status" "$credential_status" "$original_status" \
    "${ROUND_EXIT[pipeline]:--1}" "${ROUND_TESTS[pipeline]:--1}" "${ROUND_FAILURES[pipeline]:--1}" "${ROUND_ERRORS[pipeline]:--1}" "${ROUND_SKIPPED[pipeline]:--1}" \
    "${ROUND_EXIT[index_outage]:--1}" "${ROUND_TESTS[index_outage]:--1}" "${ROUND_FAILURES[index_outage]:--1}" "${ROUND_ERRORS[index_outage]:--1}" "${ROUND_SKIPPED[index_outage]:--1}" \
    "${ROUND_EXIT[outage]:--1}" "${ROUND_TESTS[outage]:--1}" "${ROUND_FAILURES[outage]:--1}" "${ROUND_ERRORS[outage]:--1}" "${ROUND_SKIPPED[outage]:--1}" \
    "${ROUND_EXIT[recovery]:--1}" "${ROUND_TESTS[recovery]:--1}" "${ROUND_FAILURES[recovery]:--1}" "${ROUND_ERRORS[recovery]:--1}" "${ROUND_SKIPPED[recovery]:--1}" \
    "$FAILURE_MESSAGE" <<'PY'
import json
import pathlib
import sys

(
    output, run_id, runtime, cleanup, credentials, original,
    pipeline_exit, pipeline_tests, pipeline_failures, pipeline_errors, pipeline_skipped,
    index_exit, index_tests, index_failures, index_errors, index_skipped,
    outage_exit, outage_tests, outage_failures, outage_errors, outage_skipped,
    recovery_exit, recovery_tests, recovery_failures, recovery_errors, recovery_skipped,
    failure,
) = sys.argv[1:]

def number(value):
    return int(value)

def round_payload(exit_code, tests, failures, errors, skipped):
    return {
        "exitCode": number(exit_code),
        "tests": number(tests),
        "failures": number(failures),
        "errors": number(errors),
        "skipped": number(skipped),
    }

payload = {
    "RunId": run_id,
    "RuntimeSucceeded": runtime == "true",
    "CleanupSucceeded": cleanup == "true",
    "CredentialFileRemaining": credentials == "true",
    "OriginalExitCode": number(original),
    "Rounds": {
        "pipeline": round_payload(pipeline_exit, pipeline_tests, pipeline_failures, pipeline_errors, pipeline_skipped),
        "index_outage": round_payload(index_exit, index_tests, index_failures, index_errors, index_skipped),
        "outage": round_payload(outage_exit, outage_tests, outage_failures, outage_errors, outage_skipped),
        "recovery": round_payload(recovery_exit, recovery_tests, recovery_failures, recovery_errors, recovery_skipped),
    },
    "Failure": failure,
}
pathlib.Path(output).write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
}

cleanup() {
  local original_status="$?"
  local cleanup_status=0
  local role name expected_id inspect_id label remaining volumes volume
  local credential_file
  trap - EXIT
  set +e

  if [[ "$original_status" -ne 0 ]]; then
    save_service_logs || cleanup_status=1
  fi

  for role in mysql rabbit es; do
    if [[ "${CONTAINER_STARTED[$role]}" == 1 ]]; then
      name="${CONTAINER_NAME[$role]}"
      expected_id="${CONTAINER_ID[$role]}"
      inspect_id="$(docker inspect --format '{{.Id}}' "$name" 2>/dev/null)"
      if [[ -z "$inspect_id" || "$inspect_id" != "$expected_id" ]]; then
        cleanup_status=1
        continue
      fi
      label="$(docker inspect --format '{{ index .Config.Labels "com.bluealbum.m2-search" }}' "$name" 2>/dev/null)"
      if [[ "$label" != "$RUN_ID" ]]; then
        cleanup_status=1
        continue
      fi
      if [[ -z "${CONTAINER_VOLUMES[$role]}" ]]; then
        record_container_volumes "$role" "$name" || cleanup_status=1
      fi
      docker rm -fv "$name" >/dev/null 2>&1 || cleanup_status=1
      remaining="$(docker ps -aq --no-trunc --filter "id=$expected_id" 2>/dev/null)"
      if [[ $? -ne 0 || "$remaining" != "" ]]; then
        cleanup_status=1
      fi
    fi
  done

  remaining="$(docker ps -aq --filter "label=com.bluealbum.m2-search=$RUN_ID" 2>/dev/null)"
  if [[ $? -ne 0 || "$remaining" != "" ]]; then
    cleanup_status=1
  fi
  volumes="$(docker volume ls -q 2>/dev/null)"
  if [[ $? -ne 0 ]]; then
    cleanup_status=1
  else
    for role in mysql rabbit es; do
      while IFS= read -r volume; do
        [[ -n "$volume" ]] || continue
        remaining="$(docker volume ls -q --filter "name=^${volume}$" 2>/dev/null)"
        if [[ $? -ne 0 || "$remaining" != "" ]]; then
          cleanup_status=1
        fi
      done <<<"${CONTAINER_VOLUMES[$role]// /$'\n'}"
    done
  fi

  for credential_file in "$MYSQL_ENV_FILE" "$RABBIT_ENV_FILE" "$TEST_ENV_FILE"; do
    if [[ -n "$credential_file" ]]; then
      rm -f -- "$credential_file" || cleanup_status=1
      [[ ! -e "$credential_file" ]] || cleanup_status=1
    fi
  done
  if [[ -n "${CREDENTIAL_DIR:-}" ]]; then
    rmdir -- "$CREDENTIAL_DIR" 2>/dev/null || true
    if [[ -e "$CREDENTIAL_DIR" ]]; then
      CREDENTIAL_FILE_REMAINING=true
      cleanup_status=1
    fi
  fi

  CLEANUP_SUCCEEDED=$([[ "$cleanup_status" -eq 0 ]] && printf true || printf false)
  RUNTIME_SUCCEEDED=$([[ "$original_status" -eq 0 && "$cleanup_status" -eq 0 ]] && printf true || printf false)
  write_checks "$RUNTIME_SUCCEEDED" "$CLEANUP_SUCCEEDED" "$CREDENTIAL_FILE_REMAINING" "$original_status" \
    || cleanup_status=1
  if [[ "$cleanup_status" -ne 0 ]]; then
    CLEANUP_SUCCEEDED=false
    RUNTIME_SUCCEEDED=false
  fi
  if [[ "$cleanup_status" -ne 0 ]]; then
    exit 1
  fi
  exit "$original_status"
}

require_command docker
require_command curl
require_command openssl
require_command python
if [[ "$MAVEN_COMMAND" == */* ]]; then
  [[ -x "$MAVEN_COMMAND" ]] || die "Maven executable is unavailable: $MAVEN_COMMAND"
else
  require_command "$MAVEN_COMMAND"
fi

assert_artifact_root_is_empty
RUN_ID="$(random_hex 5)"
MYSQL_NAME="bluealbum-m2-mysql-$RUN_ID"
RABBIT_NAME="bluealbum-m2-rabbit-$RUN_ID"
ES_NAME="bluealbum-m2-es-$RUN_ID"
SEARCH_DATABASE="ba_search_runtime_$RUN_ID"
MYSQL_ROOT_PASSWORD="$(random_hex 32)"
RABBIT_USERNAME="ba_search_$RUN_ID"
RABBIT_PASSWORD="$(random_hex 32)"
CREDENTIAL_DIR="$(mktemp -d "${TMPDIR:-/tmp}/bluealbum-m2-search.XXXXXX")" \
  || die 'cannot create temporary credential directory'
trap cleanup EXIT
chmod 700 "$CREDENTIAL_DIR" || die 'cannot restrict temporary credential directory'
MYSQL_ENV_FILE="$CREDENTIAL_DIR/mysql.env"
RABBIT_ENV_FILE="$CREDENTIAL_DIR/rabbit.env"
TEST_ENV_FILE="$CREDENTIAL_DIR/test.env"
write_env_file "$MYSQL_ENV_FILE" \
  "MYSQL_ROOT_PASSWORD=$MYSQL_ROOT_PASSWORD" \
  'MYSQL_ROOT_HOST=%' \
  "MYSQL_DATABASE=$SEARCH_DATABASE"
write_env_file "$RABBIT_ENV_FILE" \
  "RABBITMQ_DEFAULT_USER=$RABBIT_USERNAME" \
  "RABBITMQ_DEFAULT_PASS=$RABBIT_PASSWORD"

run_container mysql "$MYSQL_NAME" \
  --env-file "$MYSQL_ENV_FILE" \
  --memory 512m --memory-swap 512m \
  --publish 127.0.0.1::3306 \
  mysql:8.0
run_container rabbit "$RABBIT_NAME" \
  --env-file "$RABBIT_ENV_FILE" \
  --memory 384m --memory-swap 384m \
  --publish 127.0.0.1::5672 \
  rabbitmq:3.13-management
run_container es "$ES_NAME" \
  --env discovery.type=single-node \
  --env xpack.security.enabled=false \
  --env 'ES_JAVA_OPTS=-Xms512m -Xmx512m' \
  --volume /usr/share/elasticsearch/data \
  --memory 1024m --memory-swap 1024m \
  --publish 127.0.0.1::9200 \
  docker.elastic.co/elasticsearch/elasticsearch:8.17.10

MYSQL_PORT="$(container_port "$MYSQL_NAME" 3306/tcp)"
RABBIT_PORT="$(container_port "$RABBIT_NAME" 5672/tcp)"
ES_PORT="$(container_port "$ES_NAME" 9200/tcp)"
ES_URL="http://127.0.0.1:$ES_PORT"
capture_container_metadata mysql "$MYSQL_NAME" "$MYSQL_PORT" 512
capture_container_metadata rabbit "$RABBIT_NAME" "$RABBIT_PORT" 384
capture_container_metadata es "$ES_NAME" "$ES_PORT" 1024

export CONTENT_SEARCH_TEST_DATASOURCE_URL="jdbc:mysql://127.0.0.1:$MYSQL_PORT/$SEARCH_DATABASE?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8"
export CONTENT_SEARCH_TEST_DATABASE="$SEARCH_DATABASE"
export CONTENT_SEARCH_TEST_DB_USERNAME=root
export CONTENT_SEARCH_TEST_DB_PASSWORD="$MYSQL_ROOT_PASSWORD"
export CONTENT_SEARCH_TEST_RABBIT_PORT="$RABBIT_PORT"
export CONTENT_SEARCH_TEST_RABBIT_USERNAME="$RABBIT_USERNAME"
export CONTENT_SEARCH_TEST_RABBIT_PASSWORD="$RABBIT_PASSWORD"
export CONTENT_SEARCH_TEST_ELASTIC_URL="$ES_URL"
export CONTENT_SEARCH_TEST_EVIDENCE_DIRECTORY="$ARTIFACT_ROOT/pipeline"
export CONTENT_SEARCH_TEST_PHASE=pipeline
export SPRING_CONFIG_IMPORT=""
export JAVA_TOOL_OPTIONS='-Xmx512m -XX:ActiveProcessorCount=2'
export MAVEN_OPTS='-Xmx512m -XX:ActiveProcessorCount=2'
write_test_environment

wait_for_services
run_maven_phase pipeline pipeline_replay_order_visibility_and_rebuild
stop_role es
run_maven_phase index_outage index_outage_quarantines_and_keeps_owner_results
stop_role rabbit
run_maven_phase outage outage_keeps_events_and_uses_database
start_role rabbit
start_role es
export CONTENT_SEARCH_TEST_PHASE=recovery
export CONTENT_SEARCH_TEST_EVIDENCE_DIRECTORY="$ARTIFACT_ROOT/recovery"
write_test_environment
wait_for_services
run_maven_phase recovery recovery_replays_pending_and_failed_events

RUNTIME_SUCCEEDED=true
printf 'M2 search runtime acceptance completed for run %s\n' "$RUN_ID"
