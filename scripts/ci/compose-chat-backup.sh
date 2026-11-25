#!/usr/bin/env bash
# Sourced by the browser smoke runner for a fresh split-chat restore rehearsal.

backup_restore_compose() {
  COMPOSE_DISABLE_ENV_FILE=1 docker compose --project-name "$BACKUP_RESTORE_PROJECT" \
    --project-directory "$ROOT_DIR" --env-file "$BACKUP_RESTORE_ENV" \
    --file "$ROOT_DIR/docker-compose.yml" --file "$ROOT_DIR/docker-compose.chat.yml" \
    --file "$OVERRIDE_FILE" "$@"
}

backup_prepare_restore() {
  local restore_ports index
  local -a ports replacements=() keys=(COMPOSE_MYSQL_PORT COMPOSE_REDIS_PORT COMPOSE_RABBITMQ_PORT
    COMPOSE_RABBITMQ_MANAGEMENT_PORT COMPOSE_BACKEND_PORT COMPOSE_NOTIFICATION_PORT COMPOSE_GATEWAY_PORT COMPOSE_FRONTEND_PORT)
  restore_ports=$("$PYTHON_BIN" - "${#keys[@]}" <<'PY'
from contextlib import ExitStack
import socket
import sys

with ExitStack() as stack:
    for _ in range(int(sys.argv[1])):
        listener = stack.enter_context(socket.socket())
        listener.bind(("127.0.0.1", 0))
        print(listener.getsockname()[1])
PY
  )
  mapfile -t ports <<< "${restore_ports//$'\r'/}"
  [[ "${#ports[@]}" == "${#keys[@]}" ]] || die 'Could not select distinct restore project ports'
  for index in "${!keys[@]}"; do
    validate_port "${ports[$index]}"
    replacements+=(-e "s/^${keys[$index]}=.*/${keys[$index]}=${ports[$index]}/")
  done
  restore_gateway_port="${ports[6]}"
  [[ ! -e "$BACKUP_RESTORE_ENV" ]] || die 'Restore environment file already exists'
  BACKUP_RESTORE_ENV_OWNED=1
  sed "${replacements[@]}" "$ENV_FILE" > "$BACKUP_RESTORE_ENV"
  assert_project_unused "$BACKUP_RESTORE_PROJECT"
}

backup_pin_images() {
  sed -i \
    -e "/^  backend:$/a\    image: $PROJECT-backend:local" \
    -e "/^  chat-service:$/a\    image: $PROJECT-chat-service:local" \
    -e "/^  notification-service:$/a\    image: $PROJECT-notification-service:latest" \
    -e "/^  gateway:$/a\    image: $PROJECT-gateway:latest" "$OVERRIDE_FILE"
  cat >> "$OVERRIDE_FILE" <<EOF
  frontend:
    image: $PROJECT-frontend:latest
  chat-realtime:
    image: $PROJECT-chat-realtime:latest
EOF
}

cleanup_chat_backup() {
  local status=0
  if (( BACKUP_RESTORE_TOUCHED == 1 )); then
    backup_restore_compose logs --no-color --timestamps --tail=500 > "$LOG_DIR/restore-compose.log" 2>&1 || status=1
    backup_restore_compose down --volumes --remove-orphans > "$LOG_DIR/restore-compose-down.log" 2>&1 || status=1
    (assert_project_unused "$BACKUP_RESTORE_PROJECT") || status=1
  fi
  if (( BACKUP_RESTORE_ENV_OWNED == 1 )); then
    rm -f -- "$BACKUP_RESTORE_ENV" || status=1
  fi
  return "$status"
}

backup_db() {
  local runner="$1" service="$2" statement="$3" result
  result=$("$runner" exec -T "$service" sh -ec '
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root "$MYSQL_DATABASE" -Nse "$1"
  ' sh "$statement") || return 1
  printf '%s\n' "${result//$'\r'/}"
}

backup_verify_notification() {
  local event_id="$1" baseline="$2" attempt delivered matches
  for (( attempt = 0; attempt < 45; attempt++ )); do
    delivered=$(backup_db backup_restore_compose mysql \
      "SELECT COUNT(*) FROM notification_outbox WHERE event_id = '$event_id' AND status = 'DELIVERED'")
    [[ "$delivered" == '1' ]] && break
    sleep 2
  done
  [[ "$delivered" == '1' ]] || die 'Restored notification did not reach its receiver'
  matches=$(backup_db backup_restore_compose mysql "
    SELECT COUNT(*) FROM bluealbum_ci_notifications.notifications n
    JOIN notification_outbox o ON o.event_id = n.idempotency_key AND o.recipient_user_id = n.user_id
    JOIN bluealbum_ci_notifications.notification_stream_event e ON e.notification_id = n.id AND e.user_id = n.user_id
    JOIN bluealbum_ci_notifications.notification_stream_cursor c ON c.user_id = n.user_id
    WHERE o.event_id = '$event_id' AND e.event_kind = 'created' AND e.sequence_id = $(( baseline + 1 ))
          AND c.next_sequence = e.sequence_id")
  [[ "$matches" == '1' ]] || die 'Restored notification did not advance its prior stream cursor exactly once'
}

backup_wait_notification_history() {
  local attempt count
  for (( attempt = 0; attempt < 30; attempt++ )); do
    count=$(backup_db compose mysql "SELECT COUNT(*) FROM notification_outbox WHERE status = 'DELIVERED' AND type = 'follow'")
    [[ "$count" == '2' ]] && return 0
    sleep 2
  done
  die 'The two earlier follow notifications were not both delivered'
}

backup_run_phase() {
  local port="${2:-$gateway_port}"
  "$PYTHON_BIN" "$SCRIPT_DIR/chat-cutover-scenario.py" "$1" \
    --api-url "http://127.0.0.1:$port/api/v1" \
    --state "$CHAT_CUTOVER_STATE" --admin-email "$chat_cutover_admin"
}

run_chat_backup() {
  local notification_event baseline receipts pending started restore_seconds restore_gateway_port
  [[ "$CHAT_BACKUP_CHECK" == '1' && "$PROJECT" =~ ^bluealbum-ci-[0-9]+-[0-9]+-split-chat-backup$ ]] \
    || die 'Chat backup requires a disposable CI project'
  backup_prepare_restore
  backup_run_phase seed
  # Persist the role for the synthetic account so the restore also verifies its authorization.
  [[ "$chat_cutover_admin" == 'chat-cutover-admin@example.test' ]] || die 'Unexpected backup administrator fixture'
  backup_db compose mysql "INSERT INTO community_admin_role (user_id) SELECT id FROM users WHERE email = '$chat_cutover_admin'"
  backup_run_phase split
  backup_run_phase backup-ready
  backup_wait_notification_history

  compose stop chat-service notification-service
  backup_run_phase backup-pending
  compose stop frontend gateway backend chat-realtime redis rabbitmq
  receipts=$(backup_db compose chat-mysql 'SELECT COUNT(*) FROM chat_moderation_receipts WHERE outcome = "APPLIED"')
  pending=$(backup_db compose mysql 'SELECT COUNT(*) FROM chat_report_decisions WHERE status IN ("PENDING","CLAIMED")')
  [[ "$receipts" == '1' && "$pending" == '1' ]] || die 'Backup requires a prior receipt and a pending moderation decision'
  notification_event=$(backup_db compose mysql \
    'SELECT event_id FROM notification_outbox WHERE status IN ("PENDING","IN_FLIGHT")')
  [[ "$notification_event" =~ ^[0-9a-f-]{36}$ ]] || die 'Backup requires exactly one pending notification'
  baseline=$(backup_db compose mysql "
    SELECT c.next_sequence FROM bluealbum_ci_notifications.notification_stream_cursor c
    JOIN notification_outbox o ON o.recipient_user_id = c.user_id WHERE o.event_id = '$notification_event'")
  [[ "$baseline" =~ ^[1-9][0-9]*$ ]] || die 'Backup requires a notification stream cursor from before the outage'

  started=$SECONDS
  BACKUP_RESTORE_TOUCHED=1
  COMPOSE_DISABLE_ENV_FILE=1 pwsh -NoProfile -File "$ROOT_DIR/scripts/Test-ComposeBackup.ps1" \
    -SourceProject "$PROJECT" -RestoreProject "$BACKUP_RESTORE_PROJECT" \
    -SourceEnvironmentFile "$ENV_FILE" -RestoreEnvironmentFile "$BACKUP_RESTORE_ENV" \
    -ComposeOverrideFile "$OVERRIDE_FILE" -ArtifactDirectory "$ARTIFACT_DIR" -IncludeSplitChat \
    > "$LOG_DIR/backup-verifier.log"
  backup_run_phase backup-recovered "$restore_gateway_port"
  backup_verify_notification "$notification_event" "$baseline"
  receipts=$(backup_db backup_restore_compose chat-mysql 'SELECT COUNT(*) FROM chat_moderation_receipts WHERE outcome = "APPLIED"')
  [[ "$receipts" == '2' ]] || die 'Restore did not keep the old receipt and apply the pending decision exactly once'
  restore_seconds=$(( SECONDS - started ))
  printf 'fresh_split_restore=passed\nchat_history_and_retry=passed\nchat_receipts=%s\nnotification_replay_and_cursor=passed\nupload_bytes=passed\nrehearsal_seconds=%s\n' \
    "$receipts" "$restore_seconds" > "$ARTIFACT_DIR/chat-backup-checks.txt"
}
