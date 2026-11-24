#!/usr/bin/env bash
# Sourced only by compose-browser-smoke.sh after it creates a disposable project.

CHAT_CUTOVER_TABLES=(chat_conversations chat_members chat_messages chat_daily_usage chat_preferences)

cutover_mysql() {
  local service="$1"
  shift
  compose exec -T "$service" sh -ec '
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root "$MYSQL_DATABASE" "$@"
  ' sh "$@"
}

cutover_dump() {
  local service="$1"
  shift
  compose exec -T "$service" sh -ec '
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -u root --compact --no-create-info --skip-triggers \
      --single-transaction --skip-extended-insert --skip-no-autocommit --hex-blob --order-by-primary \
      --set-gtid-purged=OFF --no-tablespaces "$MYSQL_DATABASE" "$@"
  ' sh "$@"
}

cutover_digest() {
  local service="$1" table="$2" digest
  digest=$(cutover_dump "$service" "$table" | openssl dgst -sha256 -r) || return 1
  printf '%s' "${digest%% *}"
}

cutover_next_id() {
  local service="$1" table="$2" next_id max_id
  next_id=$(cutover_mysql "$service" -Nse "SET SESSION information_schema_stats_expiry = 0; SELECT AUTO_INCREMENT FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = '$table'") || return 1
  next_id="${next_id//$'\r'/}"
  [[ "$next_id" =~ ^[1-9][0-9]*$ ]] || die "Invalid chat AUTO_INCREMENT for $table"
  max_id=$(cutover_mysql "$service" -Nse "SELECT COALESCE(MAX(id), 0) FROM $table") || return 1
  max_id="${max_id//$'\r'/}"
  [[ "$max_id" =~ ^[0-9]+$ ]] && (( next_id > max_id )) || die "Chat AUTO_INCREMENT trails stored IDs for $table"
  printf '%s' "$next_id"
}

chat_row_integrity() {
  local service="$1" rows
  rows=$(cutover_mysql "$service" -Nse "
SELECT 'chat_conversations', COUNT(*), COALESCE(MIN(id),0), COALESCE(MAX(id),0) FROM chat_conversations
UNION ALL SELECT 'chat_members', COUNT(*), 0, 0 FROM chat_members
UNION ALL SELECT 'chat_messages', COUNT(*), COALESCE(MIN(id),0), COALESCE(MAX(id),0) FROM chat_messages
UNION ALL SELECT 'chat_daily_usage', COUNT(*), 0, 0 FROM chat_daily_usage
UNION ALL SELECT 'chat_preferences', COUNT(*), 0, 0 FROM chat_preferences
UNION ALL SELECT 'orphan_members', COUNT(*), 0, 0 FROM chat_members m
    LEFT JOIN chat_conversations c ON c.id=m.conversation_id WHERE c.id IS NULL
UNION ALL SELECT 'orphan_messages', COUNT(*), 0, 0 FROM chat_messages m
    LEFT JOIN chat_conversations c ON c.id=m.conversation_id WHERE c.id IS NULL") || return 1
  printf '%s\n' "${rows//$'\r'/}"
}

copy_chat_state() {
  local source="$1" target="$2" phase="$3" table next_id transferred before after count min_id max_id
  [[ "$source:$target" == mysql:chat-mysql || "$source:$target" == chat-mysql:mysql ]] \
    || die 'Chat data copy requires the two disposable MySQL services'

  {
    printf '%s\n' 'START TRANSACTION;' 'DELETE FROM chat_messages;' 'DELETE FROM chat_members;' \
      'DELETE FROM chat_conversations;' 'DELETE FROM chat_daily_usage;' 'DELETE FROM chat_preferences;'
    cutover_dump "$source" "${CHAT_CUTOVER_TABLES[@]}" || return 1
    printf '%s\n' 'COMMIT;'
  } | cutover_mysql "$target" --batch

  for table in chat_conversations chat_messages; do
    next_id=$(cutover_next_id "$source" "$table")
    cutover_mysql "$target" -e "ALTER TABLE $table AUTO_INCREMENT = $next_id"
    transferred=$(cutover_next_id "$target" "$table")
    [[ "$transferred" == "$next_id" ]] || die "Chat AUTO_INCREMENT diverged for $table during $phase"
    printf '%s %s next_id=%s\n' "$phase" "$table" "$next_id" >> "$CHAT_CUTOVER_EVIDENCE"
  done
  for table in "${CHAT_CUTOVER_TABLES[@]}"; do
    before=$(cutover_digest "$source" "$table")
    after=$(cutover_digest "$target" "$table")
    [[ "$before" == "$after" ]] || die "Chat data digest diverged for $table during $phase"
    printf '%s %s sha256=%s\n' "$phase" "$table" "$after" >> "$CHAT_CUTOVER_EVIDENCE"
  done
  before=$(chat_row_integrity "$source")
  after=$(chat_row_integrity "$target")
  [[ "$before" == "$after" ]] || die "Chat row counts or key ranges diverged during $phase"
  [[ "$after" == *$'orphan_members\t0\t0\t0'* && "$after" == *$'orphan_messages\t0\t0\t0'* ]] \
    || die "Chat contains orphaned business rows during $phase"
  while IFS=$'\t' read -r table count min_id max_id; do
    printf '%s %s rows=%s min_id=%s max_id=%s\n' "$phase" "$table" "$count" "$min_id" "$max_id" >> "$CHAT_CUTOVER_EVIDENCE"
  done <<<"$after"
}

freeze_chat_writers() {
  local service active
  compose stop gateway
  compose stop backend chat-service
  [[ -z "$(compose ps --status running -q gateway backend chat-service)" ]] \
    || die 'Chat cutover could not stop all application writers and ingress'
  for service in mysql chat-mysql; do
    active=$(cutover_mysql "$service" -Nse "SELECT COUNT(*) FROM information_schema.INNODB_TRX t
      JOIN information_schema.PROCESSLIST p ON p.ID = t.trx_mysql_thread_id WHERE p.DB = DATABASE()")
    [[ "${active//$'\r'/}" == '0' ]] || die "Chat database still has transactions open on $service"
  done
  printf 'maintenance_writers=stopped transactions=0\n' >> "$CHAT_CUTOVER_EVIDENCE"
}

start_chat_owner() {
  local owner="$1" admin_id="${2:-0}" destination attempt
  [[ "$admin_id" =~ ^[0-9]+$ ]] || die 'Invalid administrator fixture ID'
  if [[ "$owner" == "remote" ]]; then destination='http://chat-service:8083'; else destination='http://backend:8080'; fi
  cat > "$CHAT_CUTOVER_OVERRIDE" <<EOF
services:
  backend:
    environment:
      APP_CHAT_OWNER: "$owner"
      APP_ADMIN_USER_ID: "$admin_id"
  gateway:
    environment:
      CHAT_SERVICE_GATEWAY_URI: "$destination"
EOF
  compose up --no-deps -d --wait --wait-timeout 180 backend
  if [[ "$owner" == "remote" ]]; then
    compose up --no-deps -d --wait --wait-timeout 180 chat-service
  fi
  compose up --no-deps -d --wait --wait-timeout 180 gateway
  local gateway_url="http://127.0.0.1:$gateway_port/api/v1"
  for (( attempt = 0; attempt < 45; attempt++ )); do
    if curl --fail --silent --max-time 2 "$gateway_url/sections?page=1&size=1" >/dev/null; then return 0; fi
    sleep 1
  done
  die 'Chat cutover gateway did not become ready'
}

run_cutover_scenario() {
  "$PYTHON_BIN" "$ROOT_DIR/scripts/ci/chat-cutover-scenario.py" "$1" \
    --api-url "http://127.0.0.1:$gateway_port/api/v1" \
    --state "$CHAT_CUTOVER_STATE" --admin-email "$chat_cutover_admin" | tee -a "$CHAT_CUTOVER_EVIDENCE"
}

verify_cutover_moderation() {
  local state count version attempt
  compose stop chat-service
  run_cutover_scenario queue-offline

  for (( attempt = 0; attempt < 30; attempt++ )); do
    state=$(cutover_mysql mysql -Nse "SELECT COUNT(*) FROM chat_report_decisions WHERE status = 'PENDING' AND attempts > 0")
    [[ "${state//$'\r'/}" == '1' ]] && break
    sleep 1
  done
  [[ "${state//$'\r'/}" == '1' ]] || die 'Core did not retain the claimed decision while chat was down'
  count=$(cutover_mysql chat-mysql -Nse "SELECT COUNT(*) FROM chat_moderation_receipts")
  [[ "${count//$'\r'/}" == '0' ]] || die 'Chat applied a decision while its process was stopped'
  printf 'moderation_outage=pending_after_retry\n' >> "$CHAT_CUTOVER_EVIDENCE"

  compose up --no-deps -d --wait --wait-timeout 180 chat-service
  run_cutover_scenario recover
  count=$(cutover_mysql mysql -Nse "SELECT COUNT(*) FROM chat_report_decision_active")
  [[ "${count//$'\r'/}" == '0' ]] || die 'Core retained a chat decision after confirmation'
  version=$(cutover_mysql mysql -Nse "SELECT COUNT(*) FROM community_audit WHERE action='REPORT_HIDE' AND target_type='CHAT_MESSAGE'")
  [[ "${version//$'\r'/}" == '1' ]] || die 'Core did not audit the confirmed moderation action exactly once'
  count=$(cutover_mysql chat-mysql -Nse "SELECT COUNT(*) FROM chat_moderation_receipts WHERE outcome='APPLIED'")
  [[ "${count//$'\r'/}" == '1' ]] || die 'Chat did not apply the retried decision exactly once'
  printf 'moderation_recovery=one_receipt_one_audit\n' >> "$CHAT_CUTOVER_EVIDENCE"
}

send_cutover_decision() {
  local request="$1" expected="$2"
  printf '%s' "$request" | compose exec -T backend sh -ec '
    curl --silent --show-error --fail --max-time 8 \
      --header "X-Internal-Service-Token: $CHAT_GOVERNANCE_INTERNAL_TOKEN" \
      --header "Content-Type: application/json" --data-binary @- \
      http://chat-service:8083/internal/v1/chat/moderation-decisions
  ' | "$PYTHON_BIN" -c 'import json, sys; sys.exit(0 if json.load(sys.stdin).get("outcome") == sys.argv[1] else "Unexpected chat moderation outcome")' "$expected"
}

verify_cutover_decision_order() {
  local room message decision_id stale_id request initial final
  room=$("$PYTHON_BIN" -c 'import json, sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["stranger_room"])' "$CHAT_CUTOVER_STATE")
  [[ "$room" =~ ^[1-9][0-9]*$ ]] || die 'Invalid chat ordering fixture'
  message=$(cutover_mysql chat-mysql -Nse "SELECT MAX(id) FROM chat_messages WHERE conversation_id = $room")
  message="${message//$'\r'/}"
  [[ "$message" =~ ^[1-9][0-9]*$ ]] || die 'Chat ordering fixture has no message'
  initial=$(cutover_mysql chat-mysql -Nse "SELECT CONCAT(m.hidden, ':', c.history_revision) FROM chat_messages m JOIN chat_conversations c ON c.id = m.conversation_id WHERE m.id = $message")
  [[ "${initial//$'\r'/}" == '0:0' ]] || die 'Chat ordering fixture should start visible'

  decision_id=$("$PYTHON_BIN" -c 'import uuid; print(uuid.uuid4())')
  stale_id=$("$PYTHON_BIN" -c 'import uuid; print(uuid.uuid4())')
  request=$(printf '{"decisionId":"%s","version":"2","messageId":"%s","reportId":"1","hidden":false}' "$decision_id" "$message")
  send_cutover_decision "$request" APPLIED
  send_cutover_decision "$request" APPLIED
  request=$(printf '{"decisionId":"%s","version":"1","messageId":"%s","reportId":"1","hidden":true}' "$stale_id" "$message")
  send_cutover_decision "$request" STALE

  final=$(cutover_mysql chat-mysql -Nse "SELECT CONCAT(m.hidden, ':', c.history_revision) FROM chat_messages m JOIN chat_conversations c ON c.id = m.conversation_id WHERE m.id = $message")
  [[ "${final//$'\r'/}" == '0:0' ]] || die 'Chat replay or stale decision changed visibility or revision'
  printf 'moderation_order=duplicate_acknowledged_stale_ignored\n' >> "$CHAT_CUTOVER_EVIDENCE"
}

run_chat_cutover() {
  local admin_id load_status=0 table baseline
  [[ "$CUTOVER_MODE" == '1' && "$SPLIT_CHAT" == '1' && "$COMPOSE_TOUCHED" == '1' \
    && "$PROJECT" == bluealbum-ci-*-split-chat-cutover ]] || die 'Chat cutover requires a disposable CI project'
  : > "$CHAT_CUTOVER_EVIDENCE"
  freeze_chat_writers
  COMPOSE_ARGS+=(--file "$CHAT_CUTOVER_OVERRIDE")
  start_chat_owner local
  run_cutover_scenario seed
  admin_id=$("$PYTHON_BIN" -c 'import json, sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["accounts"]["admin"]["id"])' "$CHAT_CUTOVER_STATE")
  [[ "$admin_id" =~ ^[1-9][0-9]*$ ]] || die 'Chat cutover did not create its administrator fixture'

  freeze_chat_writers
  copy_chat_state mysql chat-mysql forward
  start_chat_owner remote "$admin_id"
  run_cutover_scenario split
  verify_cutover_moderation
  verify_cutover_decision_order
  if [[ "$CHAT_POLL_LOAD" == '1' ]]; then
    "$PYTHON_BIN" "$ROOT_DIR/scripts/ci/chat-poll-load.py" \
      --api-url "http://127.0.0.1:$gateway_port/api/v1" --state "$CHAT_CUTOVER_STATE" \
      | tee "$ARTIFACT_DIR/chat-poll-load.json" || load_status=$?
  fi

  freeze_chat_writers
  for table in "${CHAT_CUTOVER_TABLES[@]}"; do
    baseline=$(awk -v table="$table" '$1 == "forward" && $2 == table && $3 ~ /^sha256=/ {print substr($3,8)}' "$CHAT_CUTOVER_EVIDENCE")
    [[ "$baseline" == "$(cutover_digest mysql "$table")" ]] || die "Legacy chat rows changed while the split owner was active: $table"
  done
  printf 'split_legacy_business_tables=unchanged\n' >> "$CHAT_CUTOVER_EVIDENCE"
  copy_chat_state chat-mysql mysql reverse
  start_chat_owner local "$admin_id"
  run_cutover_scenario rollback
  printf 'chat_cutover=passed\n' >> "$CHAT_CUTOVER_EVIDENCE"
  return "$load_status"
}
