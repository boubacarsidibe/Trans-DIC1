#!/bin/sh
# Restauration de contrôle : restaure la dernière sauvegarde dans une base jetable, vérifie le
# résultat, mesure la durée et la compare au RTO, trace le tout, puis supprime la base jetable.
# Une sauvegarde qui n'a jamais été restaurée n'est pas considérée comme fiable.

. "$(dirname "$0")/common.sh"

setup_pg_env
check_encryption_key

check_db="${RESTORE_CHECK_DATABASE:-${DATABASE_NAME}_restore_check}"
rto="${RTO_SECONDS:-14400}"
[ "$check_db" != "$DATABASE_NAME" ] || fail "la base de contrôle doit être différente de la base de production"

started_iso="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
started_ms="$(now_ms)"
file="$(latest_backup)"
[ -n "$file" ] || fail "aucune sauvegarde à contrôler dans $BACKUP_DIR"
manifest="${file%.dump.enc}.json"

field() {
  sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p" "$manifest" 2>/dev/null
}

drop_check_db() {
  psql -X -q -d postgres -c "DROP DATABASE IF EXISTS \"$check_db\" WITH (FORCE)" >/dev/null
}

status="SUCCESS"
reason="restauration vérifiée"
drop_check_db
if ! "$(dirname "$0")/restore.sh" "$file" "$check_db"; then
  status="FAILURE"
  reason="restauration impossible"
else
  restored_version="$(psql -X -At -d "$check_db" -c \
    "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1" 2>/dev/null || true)"
  restored_tables="$(psql -X -At -d "$check_db" -c \
    "SELECT count(*) FROM pg_tables WHERE schemaname = 'public'")"
  expected_version="$(field schemaVersion)"
  expected_tables="$(field tables)"
  if [ -n "$expected_version" ] && [ "$restored_version" != "$expected_version" ]; then
    status="FAILURE"
    reason="version de schéma restaurée ${restored_version:-absente}, attendue $expected_version"
  elif [ -n "$expected_tables" ] && [ "$restored_tables" != "$expected_tables" ]; then
    status="FAILURE"
    reason="$restored_tables table(s) restaurée(s), $expected_tables attendue(s)"
  fi
fi
duration_ms=$(($(now_ms) - started_ms))
duration=$((duration_ms / 1000))
if [ "$status" = "SUCCESS" ] && [ "$duration_ms" -gt "$((rto * 1000))" ]; then
  status="FAILURE"
  reason="durée de restauration ${duration} s supérieure au RTO de ${rto} s"
fi
[ "${RESTORE_CHECK_KEEP:-no}" = "yes" ] || drop_check_db

within_rto=false
[ "$duration_ms" -le "$((rto * 1000))" ] && within_rto=true
result="{\"checkedAt\":\"$started_iso\",\"backup\":\"$(basename "$file")\",\"status\":\"$status\",\"durationSeconds\":$duration,\"durationMs\":$duration_ms,\"rtoSeconds\":$rto,\"withinRto\":$within_rto,\"reason\":\"$reason\"}"
echo "$result" >> "$BACKUP_DIR/restore-checks.log"
echo "$result" > "$BACKUP_DIR/last-restore-check.json"
record_run "backup.restore-check" "$status" "$started_iso" "$duration_ms"

log "contrôle de restauration $status : $reason ($duration s, RTO $rto s)"
[ "$status" = "SUCCESS" ]
