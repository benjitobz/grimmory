#!/bin/sh
set -e

USER_ID="${USER_ID:-1000}"
GROUP_ID="${GROUP_ID:-1000}"
APP_USER="${APP_USER:-booklore}"

if getent group "$APP_USER" >/dev/null 2>&1; then
    existing_group_id="$(getent group "$APP_USER" | cut -d: -f3)"
    if [ "$existing_group_id" != "$GROUP_ID" ]; then
        echo "ERROR: APP_USER group '$APP_USER' already exists with GID $existing_group_id, expected $GROUP_ID." >&2
        exit 1
    fi
fi

# Create group and user if they don't exist
if ! getent group "$GROUP_ID" >/dev/null 2>&1; then
    groupadd -g "$GROUP_ID" "$APP_USER"
fi

if getent passwd "$APP_USER" >/dev/null 2>&1; then
    existing_user_id="$(getent passwd "$APP_USER" | cut -d: -f3)"
    if [ "$existing_user_id" != "$USER_ID" ]; then
        echo "ERROR: APP_USER '$APP_USER' already exists with UID $existing_user_id, expected $USER_ID." >&2
        exit 1
    fi
fi

if ! getent passwd "$USER_ID" >/dev/null 2>&1; then
    useradd -u "$USER_ID" -g "$GROUP_ID" -M -s /usr/sbin/nologin "$APP_USER"
fi

# Ensure data, bookdrop, and books directories exist and are writable by the target user
mkdir -p /app/data /bookdrop /books
chown "$USER_ID:$GROUP_ID" /app/data /bookdrop /books 2>/dev/null || true

MARIADB_PID=""
APP_PID=""

shutdown() {
    if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then
        kill -TERM "$APP_PID" 2>/dev/null || true
        wait "$APP_PID" 2>/dev/null || true
    fi
    if [ -n "$MARIADB_PID" ] && kill -0 "$MARIADB_PID" 2>/dev/null; then
        kill -TERM "$MARIADB_PID" 2>/dev/null || true
        wait "$MARIADB_PID" 2>/dev/null || true
    fi
}
trap 'shutdown; exit 143' TERM
trap 'shutdown; exit 130' INT

sql_escape() {
    printf %s "$1" | sed -e 's/\\/\\\\/g' -e "s/'/''/g"
}

# Embedded MariaDB is the default unless an external database is configured.
if [ -z "${EMBEDDED_MARIADB:-}" ]; then
    if [ -n "${DATABASE_URL:-}" ] || [ -n "${DATABASE_HOST:-}" ] || [ -n "${DB_HOST:-}" ]; then
        EMBEDDED_MARIADB=false
    else
        EMBEDDED_MARIADB=true
    fi
fi

if [ "$EMBEDDED_MARIADB" = "true" ]; then
    MARIADB_DATA_DIR="${MARIADB_DATA_DIR:-/var/lib/mysql}"
    DB_NAME="${DATABASE_NAME:-grimmory}"
    DB_USER="${DATABASE_USERNAME:-grimmory}"
    DB_PASSWORD="${DATABASE_PASSWORD:-grimmory}"

    DATABASE_URL="${DATABASE_URL:-jdbc:mariadb://127.0.0.1:3306/${DB_NAME}?createDatabaseIfNotExist=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true}"
    DATABASE_USERNAME="$DB_USER"
    DATABASE_PASSWORD="$DB_PASSWORD"
    export DATABASE_URL DATABASE_USERNAME DATABASE_PASSWORD

    mkdir -p "$MARIADB_DATA_DIR" /run/mysqld
    chown "$USER_ID:$GROUP_ID" "$MARIADB_DATA_DIR" /run/mysqld 2>/dev/null || true

    if [ ! -d "$MARIADB_DATA_DIR/mysql" ]; then
        if [ -n "$(ls -A "$MARIADB_DATA_DIR" 2>/dev/null)" ]; then
            echo "ERROR: $MARIADB_DATA_DIR is not empty but contains no MariaDB system tables. Refusing to initialize over existing data." >&2
            exit 1
        fi
        echo "Initializing embedded MariaDB data directory at $MARIADB_DATA_DIR"
        gosu "$USER_ID:$GROUP_ID" mariadb-install-db \
            --datadir="$MARIADB_DATA_DIR" \
            --auth-root-authentication-method=socket \
            --skip-test-db >/dev/null
    fi

    ESCAPED_DB_NAME="$(sql_escape "$DB_NAME")"
    ESCAPED_DB_USER="$(sql_escape "$DB_USER")"
    ESCAPED_DB_PASSWORD="$(sql_escape "$DB_PASSWORD")"
    MARIADB_INIT_FILE="/run/mysqld/init.sql"
    old_umask="$(umask)"
    umask 077
    cat > "$MARIADB_INIT_FILE" <<SQL
CREATE DATABASE IF NOT EXISTS \`$ESCAPED_DB_NAME\`;
CREATE USER IF NOT EXISTS '$ESCAPED_DB_USER'@'%' IDENTIFIED BY '$ESCAPED_DB_PASSWORD';
CREATE USER IF NOT EXISTS '$ESCAPED_DB_USER'@'localhost' IDENTIFIED BY '$ESCAPED_DB_PASSWORD';
ALTER USER '$ESCAPED_DB_USER'@'%' IDENTIFIED BY '$ESCAPED_DB_PASSWORD';
ALTER USER '$ESCAPED_DB_USER'@'localhost' IDENTIFIED BY '$ESCAPED_DB_PASSWORD';
GRANT ALL PRIVILEGES ON \`$ESCAPED_DB_NAME\`.* TO '$ESCAPED_DB_USER'@'%';
GRANT ALL PRIVILEGES ON \`$ESCAPED_DB_NAME\`.* TO '$ESCAPED_DB_USER'@'localhost';
FLUSH PRIVILEGES;
SQL
    umask "$old_umask"
    chown "$USER_ID:$GROUP_ID" "$MARIADB_INIT_FILE"

    echo "Starting embedded MariaDB"
    gosu "$USER_ID:$GROUP_ID" mariadbd \
        --datadir="$MARIADB_DATA_DIR" \
        --socket=/run/mysqld/mysqld.sock \
        --bind-address=127.0.0.1 \
        --skip-name-resolve \
        --init-file="$MARIADB_INIT_FILE" &
    MARIADB_PID=$!

    i=0
    while [ $i -lt 60 ]; do
        if mariadb-admin ping -h 127.0.0.1 --silent 2>/dev/null; then
            break
        fi
        if ! kill -0 "$MARIADB_PID" 2>/dev/null; then
            echo "ERROR: embedded MariaDB exited during startup" >&2
            exit 1
        fi
        i=$((i + 1))
        sleep 1
    done
    if [ $i -ge 60 ]; then
        echo "ERROR: embedded MariaDB did not become ready within 60s" >&2
        exit 1
    fi
    rm -f "$MARIADB_INIT_FILE"
    echo "Embedded MariaDB is ready"

    (
        while kill -0 "$MARIADB_PID" 2>/dev/null; do
            sleep 10
        done
        echo "ERROR: embedded MariaDB exited unexpectedly, stopping application" >&2
        kill -TERM 1 2>/dev/null
    ) &
fi

gosu "$USER_ID:$GROUP_ID" "$@" &
APP_PID=$!
APP_EXIT=0
wait "$APP_PID" || APP_EXIT=$?
APP_PID=""
shutdown
exit "$APP_EXIT"
