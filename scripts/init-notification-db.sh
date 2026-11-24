#!/bin/sh
# The MySQL image sources this file after creating MYSQL_USER on a fresh volume.
# Keep strict options and temporary variables out of the image's entrypoint shell.
(
set -eu

for identifier in "$NOTIFICATION_DB_NAME" "$MYSQL_USER"; do
    case "$identifier" in
        ''|*[!a-zA-Z0-9_]*)
            printf '%s\n' 'Notification database/user names must contain only letters, numbers and underscores.' >&2
            exit 1
            ;;
    esac
done

mysql_admin() {
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -u root "$@"
}

notification_user=${NOTIFICATION_DB_USER:-}
notification_password=${NOTIFICATION_DB_PASSWORD:-}
migration_user=${NOTIFICATION_DB_MIGRATION_USER:-}
migration_password=${NOTIFICATION_DB_MIGRATION_PASSWORD:-}
if [ -z "$notification_user$notification_password$migration_user$migration_password" ]; then
    mysql_admin <<SQL
CREATE DATABASE IF NOT EXISTS \`$NOTIFICATION_DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON \`$NOTIFICATION_DB_NAME\`.* TO '$MYSQL_USER'@'%';
SQL
    exit 0
fi

case "$notification_user" in
    ''|[Rr][Oo][Oo][Tt]|*[!a-zA-Z0-9_]*) printf '%s\n' 'Invalid notification database account name.' >&2; exit 1 ;;
esac
case "$notification_password" in
    ''|*[!a-zA-Z0-9_+=@%./:-]*) printf '%s\n' 'Invalid notification database credential format.' >&2; exit 1 ;;
esac
if [ "$notification_user" = "$MYSQL_USER" ] || [ "${#notification_user}" -gt 32 ] ||
    [ "$NOTIFICATION_DB_NAME" = "${MYSQL_DATABASE:?Missing core database name}" ]; then
    printf '%s\n' 'Notification storage requires a separate database and account.' >&2
    exit 1
fi
core_password=${MYSQL_PASSWORD:?Missing core database credential}
if [ "${#notification_password}" -lt 24 ] || [ "${#notification_password}" -gt 128 ] ||
    [ "$notification_password" = "$core_password" ] || [ "$notification_password" = "$MYSQL_ROOT_PASSWORD" ]; then
    printf '%s\n' 'Notification storage requires a separate random credential of 24 to 128 characters.' >&2
    exit 1
fi
if [ -n "$migration_user$migration_password" ]; then
    case "$migration_user" in
        ''|[Rr][Oo][Oo][Tt]|*[!a-zA-Z0-9_]*) printf '%s\n' 'Invalid notification migration account name.' >&2; exit 1 ;;
    esac
    case "$migration_password" in
        ''|*[!a-zA-Z0-9_+=@%./:-]*) printf '%s\n' 'Invalid notification migration credential format.' >&2; exit 1 ;;
    esac
    if [ "${#migration_user}" -gt 32 ] || [ "$migration_user" = "$notification_user" ] ||
        [ "$migration_user" = "$MYSQL_USER" ] || [ "${#migration_password}" -lt 24 ] ||
        [ "${#migration_password}" -gt 128 ] || [ "$migration_password" = "$notification_password" ] ||
        [ "$migration_password" = "$core_password" ] || [ "$migration_password" = "$MYSQL_ROOT_PASSWORD" ]; then
        printf '%s\n' 'Notification migrations require a separate account and random credential of 24 to 128 characters.' >&2
        exit 1
    fi
fi

# MySQL schema grants interpret an unescaped underscore as a wildcard.
grant_database=$(printf '%s' "$NOTIFICATION_DB_NAME" | sed 's/_/\\_/g')
mysql_admin <<SQL
CREATE DATABASE IF NOT EXISTS \`$NOTIFICATION_DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '$notification_user'@'%' IDENTIFIED BY '$notification_password';
SQL

# Authenticate before granting privileges to an account that may already exist.
mysql_notification() {
    MYSQL_PWD="$notification_password" mysql --protocol=socket -u "$notification_user" "$@"
}
mysql_notification -Nse 'SELECT 1' >/dev/null

if [ -n "$migration_user" ]; then
    mysql_admin <<SQL
CREATE USER IF NOT EXISTS '$migration_user'@'%' IDENTIFIED BY '$migration_password';
SQL
    mysql_migration() {
        MYSQL_PWD="$migration_password" mysql --protocol=socket -u "$migration_user" "$@"
    }
    mysql_migration -Nse 'SELECT 1' >/dev/null
    mysql_admin <<SQL
GRANT ALL PRIVILEGES ON \`$grant_database\`.* TO '$migration_user'@'%';
SQL
    mysql_migration --database="$NOTIFICATION_DB_NAME" -Nse 'SELECT DATABASE()' >/dev/null
    if mysql_migration --database="$MYSQL_DATABASE" -Nse 'SELECT DATABASE()' >/dev/null 2>&1; then
        printf '%s\n' 'Notification migration account can still access the core database.' >&2
        exit 1
    fi

    grant_row=$(mysql_admin -Nse \
        "SELECT COUNT(*) FROM mysql.db WHERE Host = '%' AND User = '$notification_user' AND Db = REPLACE('$NOTIFICATION_DB_NAME', '_', CONCAT(CHAR(92), '_'))")
    if [ "$grant_row" = 1 ]; then
        mysql_admin <<SQL
REVOKE ALL PRIVILEGES ON \`$grant_database\`.* FROM '$notification_user'@'%';
SQL
    fi
    mysql_admin <<SQL
GRANT SELECT, INSERT, UPDATE ON \`$grant_database\`.* TO '$notification_user'@'%';
SQL
else
    mysql_admin <<SQL
GRANT ALL PRIVILEGES ON \`$grant_database\`.* TO '$notification_user'@'%';
SQL
fi

# Check the replacement credential before removing the old grant on an existing volume.
mysql_notification --database="$NOTIFICATION_DB_NAME" -Nse 'SELECT DATABASE()' >/dev/null
if mysql_notification --database="$MYSQL_DATABASE" -Nse 'SELECT DATABASE()' >/dev/null 2>&1; then
    printf '%s\n' 'Notification account can still access the core database.' >&2
    exit 1
fi
mysql_core() {
    MYSQL_PWD="$core_password" mysql --protocol=socket -u "$MYSQL_USER" "$@"
}
mysql_core --database="$MYSQL_DATABASE" -Nse 'SELECT DATABASE()' >/dev/null
legacy_grant=$(mysql_admin -Nse \
    "SELECT COUNT(*) FROM mysql.db WHERE Host = '%' AND User = '$MYSQL_USER' AND Db = '$NOTIFICATION_DB_NAME'")
if [ "$legacy_grant" = 1 ]; then
    mysql_admin <<SQL
REVOKE ALL PRIVILEGES ON \`$NOTIFICATION_DB_NAME\`.* FROM '$MYSQL_USER'@'%';
SQL
fi
if mysql_core --database="$NOTIFICATION_DB_NAME" -Nse 'SELECT DATABASE()' >/dev/null 2>&1; then
    printf '%s\n' 'Core account can still access the notification database.' >&2
    exit 1
fi
)
