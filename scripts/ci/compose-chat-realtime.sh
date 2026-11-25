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

chat_signal_counter() {
  local reason="$1"
  compose exec -T chat-service curl --fail --silent --max-time 3 http://localhost:9090/actuator/prometheus |
    awk -v reason="$reason" '
      /^bluealbum_chat_realtime_signal_dropped_total\{/ && index($0, "reason=\"" reason "\"") {
        print $NF; count++
      }
      END { if (count != 1) exit 1 }
    '
}

verify_chat_broker_failure() {
  local origin="http://127.0.0.1:$gateway_port/api/v1"
  local initial_failure initial_queue current_failure current_queue attempt
  initial_failure=$(chat_signal_counter rabbit_failure) || die 'Private chat Rabbit failure counter is unavailable'
  initial_queue=$(chat_signal_counter queue_full) || die 'Private chat queue saturation counter is unavailable'

  compose stop rabbitmq
  "$PYTHON_BIN" - "$SCRIPT_DIR" "$origin" "$CHAT_CUTOVER_STATE" <<'PY'
import json
from pathlib import Path
import sys
from uuid import uuid4

import httpx

sys.path.insert(0, sys.argv[1])
from chat_cutover_support import Api, require, send_direct

origin, state_path = sys.argv[2:]
state = json.loads(Path(state_path).read_text(encoding="utf-8"))
with httpx.Client(timeout=10, trust_env=False) as transport:
    sender = Api(transport, origin, state["accounts"]["a"]["token"])
    recipient = Api(transport, origin, state["accounts"]["b"]["token"])
    room = state["direct"]
    text = "Durable message during the isolated RabbitMQ outage"
    message = send_direct(sender, state["accounts"]["b"]["id"], text, str(uuid4()), 201)["message"]
    history = recipient.request("GET", f"/chat/conversations/{room}/messages")["items"]
    require(sum(row["id"] == message["id"] and row["seq"] == message["seq"] and row["content"] == text
                for row in history) == 1, "RabbitMQ outage lost or duplicated an authoritative message")
PY
  for (( attempt = 0; attempt < 20; attempt++ )); do
    current_failure=$(chat_signal_counter rabbit_failure) || die 'Chat metrics stopped responding during the broker outage'
    if awk -v before="$initial_failure" -v after="$current_failure" 'BEGIN { exit !(after > before) }'; then
      break
    fi
    sleep 1
  done
  (( attempt < 20 )) || die 'Real RabbitMQ outage did not increment the publish failure counter'
  current_queue=$(chat_signal_counter queue_full) || die 'Queue saturation counter stopped responding during the broker outage'
  [[ "$initial_queue" == "$current_queue" ]] || die 'RabbitMQ outage was also reported as an executor queue rejection'

  compose up -d --no-deps --wait --wait-timeout 120 rabbitmq
  wait_realtime_health "$(realtime_endpoint 1)"
  wait_realtime_health "$(realtime_endpoint 2)"
  "$PYTHON_BIN" - "$ARTIFACT_DIR/chat-publisher-metrics.json" \
    "$initial_failure" "$current_failure" "$initial_queue" "$current_queue" <<'PY'
import json
from pathlib import Path
import sys

path, before, after, queue_before, queue_after = sys.argv[1:]
Path(path).write_text(json.dumps({
    "rabbitFailureBefore": float(before), "rabbitFailureAfter": float(after),
    "queueFullBefore": float(queue_before), "queueFullAfter": float(queue_after),
    "durableMessageReadableDuringOutage": True, "bothRealtimeNodesRecovered": True,
}, indent=2) + "\n", encoding="utf-8")
PY
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
  verify_chat_broker_failure
)
