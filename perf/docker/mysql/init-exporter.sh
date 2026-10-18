#!/bin/sh
# Sourced by the MySQL image only when initializing an empty database volume.
# Keep strict options and temporary variables out of the image's entrypoint shell.
(
set -eu
case "$MYSQL_EXPORTER_PASSWORD" in
    ''|*[!a-f0-9]*) printf '%s\n' 'Exporter password must be 64 lowercase hexadecimal characters.' >&2; exit 1 ;;
esac
[ "${#MYSQL_EXPORTER_PASSWORD}" -eq 64 ] || exit 1
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -u root <<SQL
CREATE USER 'exporter'@'%' IDENTIFIED BY '$MYSQL_EXPORTER_PASSWORD' WITH MAX_USER_CONNECTIONS 3;
GRANT PROCESS, REPLICATION CLIENT, SELECT ON *.* TO 'exporter'@'%';
SQL
)
