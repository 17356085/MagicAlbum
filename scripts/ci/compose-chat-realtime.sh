#!/usr/bin/env bash
# Sourced by compose-browser-smoke.sh for its disposable two-node chat project.

realtime_container() {
  local index="$1" container
  container=$(docker ps -q --no-trunc \
    --filter "label=com.docker.compose.project=$PROJECT" \
    --filter 'label=com.docker.compose.service=chat-realtime' \
    --filter "label=com.docker.compose.container-number=$index") || return 1
  container="${container//$'\r'/}"
  [[ "$container" =~ ^[a-f0-9]{64}$ ]] || die "Expected one running realtime container at index $index"
  printf '%s\n' "$container"
}

realtime_endpoint() {
  local index="$1" address
  address=$(compose port --index "$index" chat-realtime 8092) || return 1
  address="${address//$'\r'/}"
  [[ "$address" =~ ^127\.0\.0\.1:[1-9][0-9]*$ ]] || die "Realtime node $index is missing its loopback port"
  printf '%s\n' "$address"
}

wait_realtime_marker() {
  local marker="$1" driver="$2" attempt
  for (( attempt = 0; attempt < 60; attempt++ )); do
    [[ -f "$marker" ]] && return 0
    kill -0 "$driver" 2>/dev/null || die "Realtime scenario exited before ${marker##*/}"
    sleep 1
  done
  die "Realtime scenario did not reach ${marker##*/}"
}

wait_realtime_health() {
  local address="$1" attempt
  for (( attempt = 0; attempt < 30; attempt++ )); do
    if curl --noproxy '*' --silent --fail --max-time 2 "http://$address/health" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  die 'Restarted realtime node did not become healthy'
}

run_chat_realtime_check() (
  local node_a node_b container_a container_b started_b sync_dir driver=''
  local api_url="http://127.0.0.1:$gateway_port/api/v1"
  container_a=$(realtime_container 1)
  container_b=$(realtime_container 2)
  node_a=$(realtime_endpoint 1)
  node_b=$(realtime_endpoint 2)
  [[ "$container_a" != "$container_b" && "$node_a" != "$node_b" ]] || die 'Expected two distinct realtime nodes'
  started_b=$(docker inspect --format '{{.State.StartedAt}}' "$container_b")

  "$PYTHON_BIN" "$SCRIPT_DIR/chat-cutover-scenario.py" seed --api-url "$api_url" \
    --state "$CHAT_CUTOVER_STATE" --admin-email 'chat-realtime-fixture@example.test'
  sync_dir=$(mktemp -d "$ARTIFACT_DIR/realtime-sync.XXXXXX")
  trap 'if [[ -n "$driver" ]]; then kill "$driver" 2>/dev/null || true; wait "$driver" 2>/dev/null || true; fi
    rm -f -- "$sync_dir/stop-requested" "$sync_dir/stopped" "$sync_dir/start-requested" "$sync_dir/started.tmp" "$sync_dir/started"
    rmdir -- "$sync_dir"' EXIT

  "$PYTHON_BIN" "$SCRIPT_DIR/chat-realtime-scenario.py" \
    --api-url "$api_url" --frontend-url "http://127.0.0.1:$frontend_port" \
    --node-a "ws://$node_a/api/v1/chat/realtime" --node-b "ws://$node_b/api/v1/chat/realtime" \
    --state "$CHAT_CUTOVER_STATE" --sync-dir "$sync_dir" > "$ARTIFACT_DIR/chat-realtime-checks.json" &
  driver=$!
  wait_realtime_marker "$sync_dir/stop-requested" "$driver"
  docker stop --time 5 "$container_a" >/dev/null
  : > "$sync_dir/stopped"
  wait_realtime_marker "$sync_dir/start-requested" "$driver"
  docker start "$container_a" >/dev/null
  # Docker can reassign an ephemeral published port after a container restart.
  node_a=$(realtime_endpoint 1)
  wait_realtime_health "$node_a"
  [[ "$(realtime_container 1)" == "$container_a" ]] || die 'Realtime node one was replaced during the restart'
  [[ "$(realtime_container 2)" == "$container_b" && \
     "$(docker inspect --format '{{.State.StartedAt}}' "$container_b")" == "$started_b" ]] \
    || die 'Realtime node two restarted during the outage'
  printf 'ws://%s/api/v1/chat/realtime\n' "$node_a" > "$sync_dir/started.tmp"
  mv -- "$sync_dir/started.tmp" "$sync_dir/started"
  wait "$driver"
  driver=''
)
