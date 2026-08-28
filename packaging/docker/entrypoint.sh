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

exec gosu "$USER_ID:$GROUP_ID" "$@"
