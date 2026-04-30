#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-}"
BASE_URL="${BASE_URL:-http://nginx:8080}"
USERS_MULTIPLIER="${USERS_MULTIPLIER:-2}"
THINK_TIME_S="${THINK_TIME_S:-0.3}"
STEADY_DURATION="${STEADY_DURATION:-15m}"
SOAK_VUS="${SOAK_VUS:-50}"
SOAK_DURATION="${SOAK_DURATION:-30m}"
WRITE_RATE="${WRITE_RATE:-0.2}"

COMPOSE_FILE="${COMPOSE_FILE:-perf/docker-compose.perf.yml}"
K6_COMMAND=(docker compose -f "$COMPOSE_FILE" --profile tools run --rm)

run_k6() {
  local name="$1"
  local users="$2"
  local write_rate="$3"
  local stages="$4"

  echo
  echo "== ${name}"
  echo "BASE_URL=${BASE_URL}"
  echo "USERS=${users}"
  echo "WRITE_RATE=${write_rate}"
  echo "THINK_TIME_S=${THINK_TIME_S}"
  echo "STAGES=${stages}"

  "${K6_COMMAND[@]}" \
    -e BASE_URL="$BASE_URL" \
    -e USERS="$users" \
    -e WRITE_RATE="$write_rate" \
    -e THINK_TIME_S="$THINK_TIME_S" \
    -e STAGES="$stages" \
    k6 run /scripts/core.js
}

usage() {
  cat <<'EOF'
Usage:
  bash perf/run-concurrency-tests.sh step
  bash perf/run-concurrency-tests.sh soak
  bash perf/run-concurrency-tests.sh write-matrix
  bash perf/run-concurrency-tests.sh all

Environment overrides:
  BASE_URL=http://nginx:8080
  WRITE_RATE=0.2
  THINK_TIME_S=0.3
  STEADY_DURATION=15m
  SOAK_VUS=50
  SOAK_DURATION=30m
  USERS_MULTIPLIER=2

Modes:
  step         Run 10 / 25 / 50 / 100 / 200 VU, each with a steady stage.
  soak         Run a long steady test at SOAK_VUS.
  write-matrix Run WRITE_RATE 0 / 0.1 / 0.2 / 0.5 at 50 VU.
  all          Run step, soak, and write-matrix in sequence.
EOF
}

step_tests() {
  for vus in 10 25 50 100 200; do
    local users=$((vus * USERS_MULTIPLIER))
    local stages="[{\"duration\":\"2m\",\"target\":${vus}},{\"duration\":\"${STEADY_DURATION}\",\"target\":${vus}},{\"duration\":\"2m\",\"target\":0}]"
    run_k6 "step-${vus}vu-write-${WRITE_RATE}" "$users" "$WRITE_RATE" "$stages"
  done
}

soak_test() {
  local users=$((SOAK_VUS * USERS_MULTIPLIER))
  local stages="[{\"duration\":\"2m\",\"target\":${SOAK_VUS}},{\"duration\":\"${SOAK_DURATION}\",\"target\":${SOAK_VUS}},{\"duration\":\"2m\",\"target\":0}]"
  run_k6 "soak-${SOAK_VUS}vu-${SOAK_DURATION}-write-${WRITE_RATE}" "$users" "$WRITE_RATE" "$stages"
}

write_matrix_tests() {
  local vus="${MATRIX_VUS:-50}"
  local users=$((vus * USERS_MULTIPLIER))
  local stages="[{\"duration\":\"2m\",\"target\":${vus}},{\"duration\":\"${STEADY_DURATION}\",\"target\":${vus}},{\"duration\":\"2m\",\"target\":0}]"

  for rate in 0 0.1 0.2 0.5; do
    run_k6 "write-matrix-${vus}vu-write-${rate}" "$users" "$rate" "$stages"
  done
}

case "$MODE" in
  step)
    step_tests
    ;;
  soak)
    soak_test
    ;;
  write-matrix)
    write_matrix_tests
    ;;
  all)
    step_tests
    soak_test
    write_matrix_tests
    ;;
  *)
    usage
    exit 2
    ;;
esac
