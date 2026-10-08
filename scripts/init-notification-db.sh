#!/bin/sh
# The MySQL image sources this file after creating MYSQL_USER on a fresh volume.
set -eu

for identifier in "$NOTIFICATION_DB_NAME" "$MYSQL_USER"; do
    case "$identifier" in
        ''|*[!a-zA-Z0-9_]*)
            printf '%s\n' 'Notification database/user names must contain only letters, numbers and underscores.' >&2
            exit 1
            ;;
    esac
done

MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -u root <<SQL
CREATE DATABASE IF NOT EXISTS \`$NOTIFICATION_DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON \`$NOTIFICATION_DB_NAME\`.* TO '$MYSQL_USER'@'%';
SQL
