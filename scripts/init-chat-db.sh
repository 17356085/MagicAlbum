#!/bin/sh
# Provision the opt-in chat database accounts and keep the application account
# limited to the DML used by ChatRepository. The script is safe to run again:
# existing accounts must authenticate with the supplied credentials before any
# grants are changed.
(
set -eu

chat_database=${CHAT_DB_NAME:?CHAT_DB_NAME must be set}
app_user=${CHAT_DB_USER:?CHAT_DB_USER must be set}
app_password=${CHAT_DB_PASSWORD:?CHAT_DB_PASSWORD must be set}
migration_user=${CHAT_DB_MIGRATION_USER:?CHAT_DB_MIGRATION_USER must be set}
migration_password=${CHAT_DB_MIGRATION_PASSWORD:?CHAT_DB_MIGRATION_PASSWORD must be set}
root_password=${CHAT_DB_ROOT_PASSWORD:?CHAT_DB_ROOT_PASSWORD must be set}

if [ "${MYSQL_DATABASE:-$chat_database}" != "$chat_database" ] ||
    [ "${MYSQL_USER:-$app_user}" != "$app_user" ] ||
    [ "${MYSQL_PASSWORD:-$app_password}" != "$app_password" ]; then
    printf '%s\n' 'MySQL bootstrap values must match CHAT_DB_* values.' >&2
    exit 1
fi

for identifier in "$chat_database" "$app_user" "$migration_user"; do
    case "$identifier" in
        ''|*[!a-zA-Z0-9_]*)
            printf '%s\n' 'Chat database and account names must contain only letters, numbers and underscores.' >&2
            exit 1
            ;;
    esac
done

if [ "${#chat_database}" -gt 64 ] || [ "${#app_user}" -gt 32 ] || [ "${#migration_user}" -gt 32 ] ||
    [ "$app_user" = "$migration_user" ] ||
    [ "$app_user" = "root" ] || [ "$migration_user" = "root" ]; then
    printf '%s\n' 'Chat database and account names are invalid or not distinct.' >&2
    exit 1
fi

for credential in "$app_password" "$migration_password" "$root_password"; do
    case "$credential" in
        ''|*[!a-zA-Z0-9_+=@%./:-]*)
            printf '%s\n' 'Chat database credentials contain an unsupported character.' >&2
            exit 1
            ;;
    esac
    if [ "${#credential}" -lt 24 ] || [ "${#credential}" -gt 128 ]; then
        printf '%s\n' 'Chat database credentials must contain 24 to 128 characters.' >&2
        exit 1
    fi
done

if [ "$app_password" = "$migration_password" ] ||
    [ "$app_password" = "$root_password" ] ||
    [ "$migration_password" = "$root_password" ]; then
    printf '%s\n' 'Chat database accounts require distinct credentials.' >&2
    exit 1
fi

mysql_admin() {
    MYSQL_PWD="$root_password" mysql --protocol=socket -u root "$@"
}

mysql_app() {
    MYSQL_PWD="$app_password" mysql --protocol=socket -u "$app_user" "$@"
}

mysql_migration() {
    MYSQL_PWD="$migration_password" mysql --protocol=socket -u "$migration_user" "$@"
}

# MySQL schema grants treat an underscore as a pattern character in some
# privilege statements. Escape it so that this remains one literal schema.
grant_database=$(printf '%s' "$chat_database" | sed 's/_/\\_/g')
mysql_admin <<SQL
CREATE DATABASE IF NOT EXISTS \`$chat_database\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '$app_user'@'%' IDENTIFIED BY '$app_password';
CREATE USER IF NOT EXISTS '$migration_user'@'%' IDENTIFIED BY '$migration_password';
SQL

# Do not rotate an existing credential implicitly. Authentication failures stop
# the script before any privilege changes, making re-execution recoverable.
mysql_app -Nse 'SELECT 1' >/dev/null
mysql_migration -Nse 'SELECT 1' >/dev/null

mysql_admin <<SQL
GRANT ALL PRIVILEGES ON \`$grant_database\`.* TO '$migration_user'@'%';
SQL
app_schema_grant=$(mysql_admin -Nse \
    "SELECT COUNT(*) FROM mysql.db WHERE Host = '%' AND User = '$app_user' AND Db = '$chat_database'")
if [ "$chat_database" != "$grant_database" ] && [ "$app_schema_grant" = 1 ]; then
    # Remove the wildcard grant issued by the old MySQL image bootstrap.
    mysql_admin <<SQL
REVOKE ALL PRIVILEGES ON \`$chat_database\`.* FROM '$app_user'@'%';
SQL
fi
literal_database="REPLACE('$chat_database', '_', CONCAT(CHAR(92), '_'))"
literal_app_grant=$(mysql_admin -Nse \
    "SELECT COUNT(*) FROM mysql.db WHERE Host = '%' AND User = '$app_user' AND Db = $literal_database")
if [ "$literal_app_grant" = 1 ]; then
    mysql_admin <<SQL
REVOKE ALL PRIVILEGES ON \`$grant_database\`.* FROM '$app_user'@'%';
SQL
fi
# ChatRepository's lifecycle deletes are state updates; it issues no physical
# DELETE, so SELECT/INSERT/UPDATE are the complete runtime grant set.
mysql_admin <<SQL
GRANT SELECT, INSERT, UPDATE ON \`$grant_database\`.* TO '$app_user'@'%';
SQL

mysql_app --database="$chat_database" -Nse 'SELECT DATABASE()' >/dev/null
mysql_migration --database="$chat_database" -Nse 'SELECT DATABASE()' >/dev/null

# The runtime account must not be able to select another schema in this
# isolated server. The migration account is deliberately scoped to its own
# schema; it is the only account allowed to run Flyway DDL there.
if mysql_app --database=mysql -Nse 'SELECT DATABASE()' >/dev/null 2>&1; then
    printf '%s\n' 'Chat runtime account can still access the MySQL system database.' >&2
    exit 1
fi
if mysql_migration --database=mysql -Nse 'SELECT DATABASE()' >/dev/null 2>&1; then
    printf '%s\n' 'Chat migration account can still access the MySQL system database.' >&2
    exit 1
fi

app_grant=$(mysql_admin -Nse \
    "SELECT COUNT(*) FROM mysql.db WHERE Host = '%' AND User = '$app_user' AND Db = $literal_database AND Select_priv = 'Y' AND Insert_priv = 'Y' AND Update_priv = 'Y' AND Delete_priv = 'N' AND Create_priv = 'N' AND Drop_priv = 'N' AND Grant_priv = 'N' AND Alter_priv = 'N'")
if [ "$app_grant" != 1 ]; then
    printf '%s\n' 'Chat runtime account does not have the expected SELECT/INSERT/UPDATE-only schema grant.' >&2
    exit 1
fi

migration_grant=$(mysql_admin -Nse \
    "SELECT COUNT(*) FROM mysql.db WHERE Host = '%' AND User = '$migration_user' AND Db = $literal_database")
if [ "$migration_grant" != 1 ]; then
    printf '%s\n' 'Chat migration account does not have a schema-scoped grant.' >&2
    exit 1
fi
)
