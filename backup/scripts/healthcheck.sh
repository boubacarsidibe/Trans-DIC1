#!/bin/sh
# Sain si la dernière sauvegarde réussie date de moins de BACKUP_MAX_AGE_SECONDS
# (par défaut deux intervalles) : une sauvegarde qui ne tourne plus rend le conteneur « unhealthy ».

marker="${BACKUP_DIR:-/backups}/.last-backup-success"
interval="${BACKUP_INTERVAL_SECONDS:-86400}"
max_age="${BACKUP_MAX_AGE_SECONDS:-$((interval * 2))}"

[ -f "$marker" ] || exit 1
age=$(($(date -u +%s) - $(date -u -r "$marker" +%s)))
[ "$age" -le "$max_age" ]
