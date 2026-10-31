#!/bin/sh
# Sauvegarde PostgreSQL : pg_dump au format custom, chiffré à la volée (AES-256, clé dérivée par
# PBKDF2), empreinte SHA-256, manifeste non sensible, puis application de la rétention.
# Le dump en clair n'est jamais écrit sur disque.

. "$(dirname "$0")/common.sh"
# pipefail est pris en charge par le shell de l'image (busybox ash).
# shellcheck disable=SC3040
set -o pipefail

setup_pg_env
check_encryption_key
mkdir -p "$BACKUP_DIR"

started_iso="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
started_ms="$(now_ms)"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
name="${BACKUP_PREFIX}-${stamp}.dump.enc"
target="$BACKUP_DIR/$name"
partial="$BACKUP_DIR/.${name}.partial"

on_failure() {
  rm -f "$partial"
  record_run "backup.postgres" "FAILURE" "$started_iso" "$(($(now_ms) - started_ms))"
  fail "sauvegarde interrompue"
}

log "sauvegarde de la base $DATABASE_NAME vers $name"
pg_dump --format=custom --compress=6 --dbname="$DATABASE_NAME" | encrypt -out "$partial" || on_failure
# Le fichier n'apparaît sous son nom final qu'une fois complet : une sauvegarde partielle n'est
# jamais prise pour la dernière.
mv "$partial" "$target"
(cd "$BACKUP_DIR" && sha256sum "$name" > "$name.sha256")

schema_version="$(psql -X -At -d "$DATABASE_NAME" -c \
  "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1" 2>/dev/null || true)"
table_count="$(psql -X -At -d "$DATABASE_NAME" -c \
  "SELECT count(*) FROM pg_tables WHERE schemaname = 'public'" 2>/dev/null || true)"
size="$(wc -c < "$target" | tr -d ' ')"
cat > "$BACKUP_DIR/${name%.dump.enc}.json" <<JSON
{"file":"$name","createdAt":"$started_iso","database":"$DATABASE_NAME","schemaVersion":"${schema_version}","tables":${table_count:-0},"sizeBytes":$size}
JSON

duration_ms=$(($(now_ms) - started_ms))
log "sauvegarde terminée : $size octets en $((duration_ms / 1000)) s (schéma ${schema_version:-inconnu})"
record_run "backup.postgres" "SUCCESS" "$started_iso" "$duration_ms"

"$(dirname "$0")/retention.sh"
