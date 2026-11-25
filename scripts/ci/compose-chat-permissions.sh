#!/usr/bin/env bash

# M5 ARCH-05 verification for the isolated chat Compose topology.  This file
# is sourced by compose-browser-smoke.sh after the split project is healthy;
# it deliberately has no Compose side effects when sourced.

CHAT_PERMISSIONS_PROBE_TABLE='ci_arch05_permissions_probe'

chat_permissions_env_value() {
  local service="$1"
  local variable="$2"
  local value

  # This helper is intentionally limited to non-secret wiring values.  Actual
  # passwords/tokens stay in their owning container and are consumed there.
  [[ "$variable" =~ ^[A-Z][A-Z0-9_]*$ ]] || return 1
  [[ "$variable" != *PASSWORD* && "$variable" != *TOKEN* && "$variable" != *SECRET* && "$variable" != *KEY* ]] \
    || return 1
  value="$(compose exec -T "$service" sh -ec 'printenv "$1"' sh "$variable" </dev/null)" || return 1
  printf '%s' "${value//$'\r'/}"
}

chat_permissions_mysql() {
  local service="$1"
  local role="$2"
  local host="$3"
  local database="$4"
  local statement="$5"

  # Role names select credentials inside the target container.  No password is
  # passed as a docker/Compose argument, so process listings and CI diagnostics
  # cannot expose it.
  compose exec -T "$service" sh -ec '
    case "$1" in
      core-runtime|core-migration)
        db_user="$MYSQL_USER"
        db_password="$MYSQL_PASSWORD"
        ;;
      notification-runtime)
        db_user="$NOTIFICATION_DB_USER"
        db_password="$NOTIFICATION_DB_PASSWORD"
        ;;
      notification-migration)
        db_user="$NOTIFICATION_DB_MIGRATION_USER"
        db_password="$NOTIFICATION_DB_MIGRATION_PASSWORD"
        ;;
      chat-runtime)
        db_user="$CHAT_DB_USER"
        db_password="$CHAT_DB_PASSWORD"
        ;;
      chat-migration)
        db_user="$CHAT_DB_MIGRATION_USER"
        db_password="$CHAT_DB_MIGRATION_PASSWORD"
        ;;
      *)
        exit 64
        ;;
    esac
    MYSQL_PWD="$db_password" exec mysql --protocol=tcp --connect-timeout=5 --raw \
      -h "$2" -u "$db_user" "$3" -Nse "$4"
  ' sh "$role" "$host" "$database" "$statement" </dev/null
}

chat_permissions_root_mysql() {
  local service="$1"
  local host="$2"
  local database="$3"
  local statement="$4"

  compose exec -T "$service" sh -ec '
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --protocol=tcp --connect-timeout=5 \
      -h "$1" -u root "$2" -Nse "$3"
  ' sh "$host" "$database" "$statement" </dev/null
}

chat_permissions_http_status() {
  local caller="$1"
  local url="$2"
  local token_variable="$3"
  local body="$4"

  compose exec -T "$caller" sh -ec '
    case "$1" in
      CHAT_INTERNAL_SERVICE_TOKEN)
        token="$CHAT_INTERNAL_SERVICE_TOKEN"
        ;;
      CHAT_GOVERNANCE_INTERNAL_TOKEN)
        token="$CHAT_GOVERNANCE_INTERNAL_TOKEN"
        ;;
      INTERNAL_SERVICE_TOKEN)
        token="$INTERNAL_SERVICE_TOKEN"
        ;;
      *)
        exit 64
        ;;
    esac
    curl --request POST --silent --show-error --output /dev/null --write-out "%{http_code}" \
      --connect-timeout 5 --max-time 15 \
      -H "Content-Type: application/json" \
      -H "X-Internal-Service-Token: $token" \
      --data-raw "$3" "$2" 2>/dev/null
  ' sh "$token_variable" "$url" "$body" </dev/null
}

chat_permissions_require_numeric() {
  local value="$1"
  value="${value//$'\r'/}"
  [[ "$value" =~ ^[0-9]+$ ]] || return 1
  return 0
}

verify_chat_permissions() {
  [[ "${PROJECT:-}" =~ ^bluealbum-ci-[a-z0-9-]+-auth-timing$ ]] \
    || die 'ARCH-05 permissions check requires the isolated authorization project'
  [[ -n "${ARTIFACT_DIR:-}" ]] || die 'ARCH-05 permissions check requires ARTIFACT_DIR'
  declare -F compose >/dev/null 2>&1 || die 'ARCH-05 permissions check requires the isolated Compose function'

  local evidence_file="$ARTIFACT_DIR/chat-permissions-checks.txt"
  local endpoint_file="$ARTIFACT_DIR/chat-permissions-private-endpoints.txt"
  local core_database notification_database chat_database
  local core_user notification_user notification_migration_user chat_user chat_migration_user
  local backend_user notification_service_user notification_flyway_user chat_service_user chat_flyway_user
  local result attempt status
  local -a evidence=() endpoint_evidence=()

  mkdir -p "$ARTIFACT_DIR" || die 'ARCH-05 permissions evidence directory could not be created'

  # Read only schema/account names.  Secrets are never read into the host
  # shell; mysql and curl consume them from the app/database containers.
  core_database="$(chat_permissions_env_value mysql MYSQL_DATABASE)" \
    || die 'ARCH-05 could not read the core database name from mysql'
  notification_database="$(chat_permissions_env_value mysql NOTIFICATION_DB_NAME)" \
    || die 'ARCH-05 could not read the notification database name from mysql'
  chat_database="$(chat_permissions_env_value chat-mysql CHAT_DB_NAME)" \
    || die 'ARCH-05 could not read the chat database name from chat-mysql'
  core_user="$(chat_permissions_env_value mysql MYSQL_USER)" \
    || die 'ARCH-05 could not read the core database account from mysql'
  notification_user="$(chat_permissions_env_value mysql NOTIFICATION_DB_USER)" \
    || die 'ARCH-05 could not read the notification runtime account from mysql'
  notification_migration_user="$(chat_permissions_env_value mysql NOTIFICATION_DB_MIGRATION_USER)" \
    || die 'ARCH-05 could not read the notification migration account from mysql'
  chat_user="$(chat_permissions_env_value chat-mysql CHAT_DB_USER)" \
    || die 'ARCH-05 could not read the chat runtime account from chat-mysql'
  chat_migration_user="$(chat_permissions_env_value chat-mysql CHAT_DB_MIGRATION_USER)" \
    || die 'ARCH-05 could not read the chat migration account from chat-mysql'

  [[ "$core_database" =~ ^[A-Za-z0-9_]+$ && -n "$core_database" ]] \
    || die 'ARCH-05 core database name is invalid'
  [[ "$notification_database" =~ ^[A-Za-z0-9_]+$ && -n "$notification_database" ]] \
    || die 'ARCH-05 notification database name is invalid'
  [[ "$chat_database" =~ ^[A-Za-z0-9_]+$ && -n "$chat_database" ]] \
    || die 'ARCH-05 chat database name is invalid'
  [[ "$core_user" =~ ^[A-Za-z0-9_]+$ && -n "$core_user" ]] \
    || die 'ARCH-05 core database account is invalid'
  [[ "$notification_user" =~ ^[A-Za-z0-9_]+$ && -n "$notification_user" ]] \
    || die 'ARCH-05 notification runtime account is invalid'
  [[ "$notification_migration_user" =~ ^[A-Za-z0-9_]+$ && -n "$notification_migration_user" ]] \
    || die 'ARCH-05 notification migration account is invalid'
  [[ "$chat_user" =~ ^[A-Za-z0-9_]+$ && -n "$chat_user" ]] \
    || die 'ARCH-05 chat runtime account is invalid'
  [[ "$chat_migration_user" =~ ^[A-Za-z0-9_]+$ && -n "$chat_migration_user" ]] \
    || die 'ARCH-05 chat migration account is invalid'

  # Confirm the accounts actually wired into the three application containers
  # are the accounts whose grants are being exercised below.
  backend_user="$(chat_permissions_env_value backend DB_USER)" \
    || die 'ARCH-05 could not read the core runtime account from backend'
  notification_service_user="$(chat_permissions_env_value notification-service NOTIFICATION_DATASOURCE_USERNAME)" \
    || die 'ARCH-05 could not read the notification runtime account from notification-service'
  notification_flyway_user="$(chat_permissions_env_value notification-service SPRING_FLYWAY_USER)" \
    || die 'ARCH-05 could not read the notification migration account from notification-service'
  chat_service_user="$(chat_permissions_env_value chat-service SPRING_DATASOURCE_USERNAME)" \
    || die 'ARCH-05 could not read the chat runtime account from chat-service'
  chat_flyway_user="$(chat_permissions_env_value chat-service SPRING_FLYWAY_USER)" \
    || die 'ARCH-05 could not read the chat migration account from chat-service'
  [[ "$backend_user" == "$core_user" ]] \
    || die 'ARCH-05 core runtime account wiring does not match mysql'
  [[ "$notification_service_user" == "$notification_user" ]] \
    || die 'ARCH-05 notification runtime account wiring does not match mysql'
  [[ "$notification_flyway_user" == "$notification_migration_user" ]] \
    || die 'ARCH-05 notification migration account wiring does not match mysql'
  [[ "$chat_service_user" == "$chat_user" ]] \
    || die 'ARCH-05 chat runtime account wiring does not match chat-mysql'
  [[ "$chat_flyway_user" == "$chat_migration_user" ]] \
    || die 'ARCH-05 chat migration account wiring does not match chat-mysql'

  # Six effective (configured-account, owned-schema) pairs.  Core runtime and
  # migration intentionally remain one account in this Compose profile.
  local pair_label pair_service pair_role pair_host pair_database pair_table pair_result pair_grants escaped_database
  local owner_select_count=0
  while IFS='|' read -r pair_label pair_service pair_role pair_host pair_database pair_table; do
    [[ -n "$pair_label" ]] || continue
    pair_result="$(chat_permissions_mysql "$pair_service" "$pair_role" "$pair_host" "$pair_database" \
      "SELECT COUNT(*) FROM $pair_table")" \
      || die "ARCH-05 positive SELECT failed for $pair_label"
    chat_permissions_require_numeric "$pair_result" \
      || die "ARCH-05 positive SELECT returned an invalid result for $pair_label"
    pair_grants="$(chat_permissions_mysql "$pair_service" "$pair_role" "$pair_host" "$pair_database" \
      'SHOW GRANTS FOR CURRENT_USER()')" \
      || die "ARCH-05 could not inspect the effective grant for $pair_label"
    # SHOW GRANTS preserves the escapes that make underscores literal in MySQL schema grants.
    escaped_database=${pair_database//_/'\_'}
    [[ "$pair_grants" == *" ON \`$pair_database\`.* TO "* || \
       "$pair_grants" == *" ON \`$escaped_database\`.* TO "* ]] \
      || die "ARCH-05 effective grant was not scoped to the owned schema for $pair_label"
    [[ "$pair_grants" == *SELECT* || "$pair_grants" == *'ALL PRIVILEGES'* ]] \
      || die "ARCH-05 effective grant did not include SELECT for $pair_label"
    evidence+=("${pair_label}=select_granted_and_effective_grant_verified")
    owner_select_count=$((owner_select_count + 1))
  done <<EOF
core_runtime|mysql|core-runtime|127.0.0.1|$core_database|users
core_migration_effective_same_account|mysql|core-migration|127.0.0.1|$core_database|users
notification_runtime|mysql|notification-runtime|127.0.0.1|$notification_database|notifications
notification_migration|mysql|notification-migration|127.0.0.1|$notification_database|notifications
chat_runtime|chat-mysql|chat-runtime|127.0.0.1|$chat_database|chat_messages
chat_migration|chat-mysql|chat-migration|127.0.0.1|$chat_database|chat_messages
EOF
  [[ "$owner_select_count" -eq 6 ]] \
    || die "ARCH-05 owner SELECT coverage expected 6 cases, got $owner_select_count"
  evidence+=("owner_select_cases=$owner_select_count")
  evidence+=("effective_pairs=6")
  evidence+=("core_runtime_migration_role=one_configured_account_verified")

  # Confirm the DML used by each owner is present without changing a row.  The
  # cross-schema matrix below separately proves that these rights do not leak.
  local dml_label dml_service dml_role dml_host dml_database dml_table
  local owner_update_count=0
  while IFS='|' read -r dml_label dml_service dml_role dml_host dml_database dml_table; do
    if ! chat_permissions_mysql "$dml_service" "$dml_role" "$dml_host" "$dml_database" \
      "UPDATE $dml_table SET id = id WHERE 1 = 0" >/dev/null 2>&1; then
      die "ARCH-05 owner DML grant failed for $dml_label"
    fi
    evidence+=("${dml_label}=nonmutating_update_granted")
    owner_update_count=$((owner_update_count + 1))
  done <<EOF
core_runtime|mysql|core-runtime|127.0.0.1|$core_database|users
notification_runtime|mysql|notification-runtime|127.0.0.1|$notification_database|notifications
notification_migration|mysql|notification-migration|127.0.0.1|$notification_database|notifications
chat_runtime|chat-mysql|chat-runtime|127.0.0.1|$chat_database|chat_messages
chat_migration|chat-mysql|chat-migration|127.0.0.1|$chat_database|chat_messages
EOF
  [[ "$owner_update_count" -eq 5 ]] \
    || die "ARCH-05 owner UPDATE coverage expected 5 cases, got $owner_update_count"
  evidence+=("owner_update_cases=$owner_update_count")

  # Every non-owner account must be unable to read another owner's schema.
  local foreign_label foreign_service foreign_role foreign_host foreign_database foreign_table foreign_result foreign_update
  local foreign_select_count=0 foreign_update_count=0
  while IFS='|' read -r foreign_label foreign_service foreign_role foreign_host foreign_database foreign_table; do
    [[ -n "$foreign_label" ]] || continue
    if foreign_result="$(chat_permissions_mysql "$foreign_service" "$foreign_role" "$foreign_host" \
      "$foreign_database" "SELECT COUNT(*) FROM $foreign_table" 2>&1)"; then
      die "ARCH-05 foreign database read unexpectedly succeeded for $foreign_label"
    fi
    [[ "$foreign_result" =~ ERROR\ (1044|1045|1142) ]] \
      || die "ARCH-05 foreign database read did not fail with an authorization error for $foreign_label"
    evidence+=("${foreign_label}=read_denied")
    foreign_select_count=$((foreign_select_count + 1))
    if foreign_update="$(chat_permissions_mysql "$foreign_service" "$foreign_role" "$foreign_host" \
      "$foreign_database" "UPDATE $foreign_table SET id = id WHERE 1 = 0" 2>&1)"; then
      die "ARCH-05 foreign database nonmutating update unexpectedly succeeded for $foreign_label"
    fi
    [[ "$foreign_update" =~ ERROR\ (1044|1045|1142) ]] \
      || die "ARCH-05 foreign database nonmutating update did not fail with an authorization error for $foreign_label"
    evidence+=("${foreign_label}=nonmutating_update_denied")
    foreign_update_count=$((foreign_update_count + 1))
  done <<EOF
core_to_notification|mysql|core-runtime|127.0.0.1|$notification_database|notifications
core_to_chat|mysql|core-runtime|chat-mysql|$chat_database|chat_messages
notification_to_core|mysql|notification-runtime|127.0.0.1|$core_database|users
notification_migration_to_core|mysql|notification-migration|127.0.0.1|$core_database|users
notification_to_chat|mysql|notification-runtime|chat-mysql|$chat_database|chat_messages
notification_migration_to_chat|mysql|notification-migration|chat-mysql|$chat_database|chat_messages
chat_to_core|chat-mysql|chat-runtime|mysql|$core_database|users
chat_to_notification|chat-mysql|chat-runtime|mysql|$notification_database|notifications
chat_migration_to_core|chat-mysql|chat-migration|mysql|$core_database|users
chat_migration_to_notification|chat-mysql|chat-migration|mysql|$notification_database|notifications
EOF
  [[ "$foreign_select_count" -eq 10 ]] \
    || die "ARCH-05 foreign SELECT coverage expected 10 cases, got $foreign_select_count"
  [[ "$foreign_update_count" -eq 10 ]] \
    || die "ARCH-05 foreign UPDATE coverage expected 10 cases, got $foreign_update_count"
  evidence+=("foreign_select_cases=$foreign_select_count")
  evidence+=("foreign_update_cases=$foreign_update_count")

  # Keep a single deterministic probe name and remove it before every owner
  # check and again after every successful DDL check.  Notification/chat
  # runtime accounts must be denied CREATE; the core shared account is its
  # effective migration owner and is therefore verified as a DDL owner.
  chat_permissions_root_mysql mysql 127.0.0.1 "$core_database" \
    "DROP TABLE IF EXISTS $CHAT_PERMISSIONS_PROBE_TABLE" >/dev/null 2>&1 \
    || die 'ARCH-05 could not clear the core DDL probe table'
  chat_permissions_root_mysql mysql 127.0.0.1 "$notification_database" \
    "DROP TABLE IF EXISTS $CHAT_PERMISSIONS_PROBE_TABLE" >/dev/null 2>&1 \
    || die 'ARCH-05 could not clear the notification DDL probe table'
  chat_permissions_root_mysql chat-mysql 127.0.0.1 "$chat_database" \
    "DROP TABLE IF EXISTS $CHAT_PERMISSIONS_PROBE_TABLE" >/dev/null 2>&1 \
    || die 'ARCH-05 could not clear the chat DDL probe table'

  local ddl_label ddl_service ddl_role ddl_host ddl_database ddl_result
  local denied_ddl_count=0
  while IFS='|' read -r ddl_label ddl_service ddl_role ddl_host ddl_database; do
    if ddl_result="$(chat_permissions_mysql "$ddl_service" "$ddl_role" "$ddl_host" "$ddl_database" \
      "CREATE TABLE $CHAT_PERMISSIONS_PROBE_TABLE (id INT NOT NULL)" 2>&1)"; then
      chat_permissions_root_mysql "$ddl_service" "$ddl_host" "$ddl_database" \
        "DROP TABLE IF EXISTS $CHAT_PERMISSIONS_PROBE_TABLE" >/dev/null 2>&1 || true
      die "ARCH-05 runtime DDL unexpectedly succeeded for $ddl_label"
    fi
    [[ "$ddl_result" =~ ERROR\ 1142 ]] \
      || die "ARCH-05 runtime DDL did not fail with ERROR 1142 for $ddl_label"
    evidence+=("${ddl_label}=ddl_denied")
    denied_ddl_count=$((denied_ddl_count + 1))
  done <<EOF
notification_runtime_ddl|mysql|notification-runtime|127.0.0.1|$notification_database
chat_runtime_ddl|chat-mysql|chat-runtime|127.0.0.1|$chat_database
EOF
  [[ "$denied_ddl_count" -eq 2 ]] \
    || die "ARCH-05 denied DDL coverage expected 2 cases, got $denied_ddl_count"
  evidence+=("denied_ddl_cases=$denied_ddl_count")

  local allowed_ddl_count=0
  while IFS='|' read -r ddl_label ddl_service ddl_role ddl_host ddl_database; do
    if ! chat_permissions_mysql "$ddl_service" "$ddl_role" "$ddl_host" "$ddl_database" \
      "CREATE TABLE $CHAT_PERMISSIONS_PROBE_TABLE (id INT NOT NULL)" >/dev/null 2>&1; then
      die "ARCH-05 migration DDL failed for $ddl_label"
    fi
    chat_permissions_root_mysql "$ddl_service" "$ddl_host" "$ddl_database" \
      "DROP TABLE IF EXISTS $CHAT_PERMISSIONS_PROBE_TABLE" >/dev/null 2>&1 \
      || die "ARCH-05 could not clean the DDL probe for $ddl_label"
    evidence+=("${ddl_label}=ddl_owner_verified")
    allowed_ddl_count=$((allowed_ddl_count + 1))
  done <<EOF
core_effective_migration|mysql|core-migration|127.0.0.1|$core_database
notification_migration|mysql|notification-migration|127.0.0.1|$notification_database
chat_migration|chat-mysql|chat-migration|127.0.0.1|$chat_database
EOF
  [[ "$allowed_ddl_count" -eq 3 ]] \
    || die "ARCH-05 allowed migration DDL coverage expected 3 cases, got $allowed_ddl_count"
  evidence+=("allowed_migration_ddl_cases=$allowed_ddl_count")
  evidence+=("core_runtime_effective_ddl_role=shared_migration_owner")

  # Exercise the private HTTP contracts from the actual application
  # containers.  The fixture IDs/payload are synthetic and cannot mutate
  # business data: profiles returns a missing-ID read, chat evidence rejects
  # an invalid ID before any lookup, and notifications rejects an empty request
  # at validation before any INSERT.
  local core_private_status chat_private_status notification_private_status
  local core_wrong_status chat_wrong_status notification_wrong_status
  core_private_status="$(chat_permissions_http_status chat-service \
    'http://backend:8080/internal/v1/users/public-profiles:batchGet' \
    CHAT_INTERNAL_SERVICE_TOKEN '{"userIds":[9223372036854775807]}')" \
    || die 'ARCH-05 core private endpoint probe failed to execute'
  core_private_status="${core_private_status//$'\r'/}"
  [[ "$core_private_status" == 2?? ]] \
    || die 'ARCH-05 core private endpoint rejected its configured chat credential'
  core_wrong_status="$(chat_permissions_http_status backend \
    'http://backend:8080/internal/v1/users/public-profiles:batchGet' \
    INTERNAL_SERVICE_TOKEN '{"userIds":[9223372036854775807]}')" \
    || die 'ARCH-05 core wrong-credential probe failed to execute'
  core_wrong_status="${core_wrong_status//$'\r'/}"
  [[ "$core_wrong_status" == 401 || "$core_wrong_status" == 403 ]] \
    || die 'ARCH-05 core private endpoint accepted a credential for another owner'
  endpoint_evidence+=("core_private_correct=accepted")
  endpoint_evidence+=("core_private_wrong_use=denied_${core_wrong_status}")
  local core_gateway_status
  core_gateway_status="$(chat_permissions_http_status chat-service \
    'http://gateway:9000/internal/v1/users/public-profiles:batchGet' \
    CHAT_INTERNAL_SERVICE_TOKEN '{"userIds":[9223372036854775807]}')" \
    || die 'ARCH-05 core gateway probe failed to execute'
  core_gateway_status="${core_gateway_status//$'\r'/}"
  [[ "$core_gateway_status" == 404 ]] \
    || die 'ARCH-05 gateway exposed or rewrote the core private endpoint'
  endpoint_evidence+=("core_private_gateway=denied_404")

  chat_private_status="$(chat_permissions_http_status backend \
    'http://chat-service:8083/internal/v1/chat/report-evidence' \
    CHAT_GOVERNANCE_INTERNAL_TOKEN '{"messageId":"invalid"}')" \
    || die 'ARCH-05 chat private endpoint probe failed to execute'
  chat_private_status="${chat_private_status//$'\r'/}"
  [[ "$chat_private_status" == 400 ]] \
    || die 'ARCH-05 chat private endpoint did not validate the synthetic fixture after authentication'
  chat_wrong_status="$(chat_permissions_http_status backend \
    'http://chat-service:8083/internal/v1/chat/report-evidence' \
    CHAT_INTERNAL_SERVICE_TOKEN '{"messageId":"invalid"}')" \
    || die 'ARCH-05 chat wrong-credential probe failed to execute'
  chat_wrong_status="${chat_wrong_status//$'\r'/}"
  [[ "$chat_wrong_status" == 401 || "$chat_wrong_status" == 403 ]] \
    || die 'ARCH-05 chat private endpoint accepted the service credential for governance'
  endpoint_evidence+=("chat_private_correct=accepted_validation_${chat_private_status}")
  endpoint_evidence+=("chat_private_wrong_use=denied_${chat_wrong_status}")
  local chat_gateway_status
  chat_gateway_status="$(chat_permissions_http_status backend \
    'http://gateway:9000/internal/v1/chat/report-evidence' \
    CHAT_GOVERNANCE_INTERNAL_TOKEN '{"messageId":"invalid"}')" \
    || die 'ARCH-05 chat gateway probe failed to execute'
  chat_gateway_status="${chat_gateway_status//$'\r'/}"
  [[ "$chat_gateway_status" == 404 ]] \
    || die 'ARCH-05 gateway exposed or rewrote the chat private endpoint'
  endpoint_evidence+=("chat_private_gateway=denied_404")

  notification_private_status="$(chat_permissions_http_status backend \
    'http://notification-service:8081/api/v1/notifications/internal' \
    INTERNAL_SERVICE_TOKEN '{"userId":9223372036854775807,"type":"","title":"","content":""}')" \
    || die 'ARCH-05 notification private endpoint probe failed to execute'
  notification_private_status="${notification_private_status//$'\r'/}"
  [[ "$notification_private_status" == 400 ]] \
    || die 'ARCH-05 notification private endpoint did not validate the synthetic request after authentication'
  notification_wrong_status="$(chat_permissions_http_status backend \
    'http://notification-service:8081/api/v1/notifications/internal' \
    CHAT_INTERNAL_SERVICE_TOKEN '{"userId":9223372036854775807,"type":"","title":"","content":""}')" \
    || die 'ARCH-05 notification wrong-credential probe failed to execute'
  notification_wrong_status="${notification_wrong_status//$'\r'/}"
  [[ "$notification_wrong_status" == 401 || "$notification_wrong_status" == 403 ]] \
    || die 'ARCH-05 notification private endpoint accepted the chat service credential'
  endpoint_evidence+=("notification_private_correct=accepted_validation_${notification_private_status}")
  endpoint_evidence+=("notification_private_wrong_use=denied_${notification_wrong_status}")
  local notification_gateway_status
  notification_gateway_status="$(chat_permissions_http_status backend \
    'http://gateway:9000/api/v1/notifications/internal' \
    INTERNAL_SERVICE_TOKEN '{"userId":9223372036854775807,"type":"","title":"","content":""}')" \
    || die 'ARCH-05 notification gateway probe failed to execute'
  notification_gateway_status="${notification_gateway_status//$'\r'/}"
  # Compose has a broad /api/v1/** fallback to backend, so this also proves the
  # gateway boundary filter denies the private path before that fallback.
  [[ "$notification_gateway_status" == 404 ]] \
    || die 'ARCH-05 gateway exposed the notification private endpoint'
  endpoint_evidence+=("notification_private_gateway=denied_${notification_gateway_status}")

  evidence+=("probe_table=$CHAT_PERMISSIONS_PROBE_TABLE")
  evidence+=("probe_table_cleanup=verified")
  evidence+=("cross_schema_reads=all_listed_attempts_denied")
  evidence+=("cross_schema_updates=all_listed_nonmutating_attempts_denied")
  evidence+=("private_http=core_chat_notification_verified")
  evidence+=("public_routing=all_listed_private_paths_denied")
  evidence+=("project=$PROJECT")
  printf '%s\n' "${evidence[@]}" > "$evidence_file"
  printf '%s\n' "${endpoint_evidence[@]}" > "$endpoint_file"
}
