#!/bin/sh
# Fonctions partagées par les scripts de sauvegarde. Aucun secret n'est jamais affiché :
# le mot de passe passe par PGPASSWORD et la clé de chiffrement par l'environnement d'openssl.

set -eu

BACKUP_DIR="${BACKUP_DIR:-/backups}"
BACKUP_PREFIX="${BACKUP_PREFIX:-trans_dic1}"

log() {
  printf '%s [backup] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"
}

fail() {
  log "ERREUR : $*"
  exit 1
}

# Vérifie qu'une variable obligatoire est renseignée, sans afficher sa valeur.
require_env() {
  for name in "$@"; do
    eval "value=\${$name:-}"
    [ -n "$value" ] || fail "la variable $name est obligatoire"
  done
}

# Connexion PostgreSQL : variables standard de libpq, alimentées par celles de la plateforme.
setup_pg_env() {
  require_env DATABASE_HOST DATABASE_NAME DATABASE_USER DATABASE_PASSWORD
  export PGHOST="$DATABASE_HOST"
  export PGPORT="${DATABASE_PORT:-5432}"
  export PGUSER="$DATABASE_USER"
  export PGPASSWORD="$DATABASE_PASSWORD"
  export PGCONNECT_TIMEOUT="${PGCONNECT_TIMEOUT:-10}"
  # Les messages NOTICE (« n'existe pas, ignoré ») n'apportent rien aux logs.
  export PGOPTIONS="${PGOPTIONS:--c client_min_messages=warning}"
}

# La clé doit être longue : openssl en dérive la clé AES par PBKDF2.
check_encryption_key() {
  require_env BACKUP_ENCRYPTION_KEY
  [ "${#BACKUP_ENCRYPTION_KEY}" -ge 32 ] || fail "BACKUP_ENCRYPTION_KEY doit contenir au moins 32 caractères"
}

encrypt() {
  openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass env:BACKUP_ENCRYPTION_KEY "$@"
}

decrypt() {
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:BACKUP_ENCRYPTION_KEY "$@"
}

# Sauvegarde la plus récente (les noms contiennent un horodatage UTC triable).
latest_backup() {
  find "$BACKUP_DIR" -maxdepth 1 -type f -name "${BACKUP_PREFIX}-*.dump.enc" | sort | tail -n 1
}

# Trace le résultat dans job_run, la table d'état des tâches planifiées de la plateforme (ARCH-06).
# Sans effet bloquant : une base indisponible ne doit pas masquer le résultat dans les logs.
record_run() {
  job="$1"
  status="$2"
  started="$3"
  duration_ms="$4"
  [ "${BACKUP_RECORD_JOB_RUN:-true}" = "true" ] || return 0
  success=0
  failure=0
  [ "$status" = "SUCCESS" ] && success=1
  [ "$status" = "FAILURE" ] && failure=1
  psql -q -X -v ON_ERROR_STOP=1 -d "$DATABASE_NAME" \
    -v job="$job" -v status="$status" -v started="$started" -v duration="$duration_ms" \
    -v success="$success" -v failure="$failure" >/dev/null 2>&1 <<'SQL' || log "résultat non tracé dans job_run (table absente ou base indisponible)"
INSERT INTO job_run(name, last_status, last_started_at, last_duration_ms, success_count, failure_count, skipped_count)
VALUES (:'job', :'status', :'started'::timestamptz, :'duration'::bigint, :'success'::bigint, :'failure'::bigint, 0)
ON CONFLICT (name) DO UPDATE SET
  last_status = EXCLUDED.last_status,
  last_started_at = EXCLUDED.last_started_at,
  last_duration_ms = EXCLUDED.last_duration_ms,
  success_count = job_run.success_count + EXCLUDED.success_count,
  failure_count = job_run.failure_count + EXCLUDED.failure_count;
SQL
}

now_ms() {
  # Horloge monotone du noyau, au centième de seconde : date de busybox ne connaît pas %N.
  awk '{ printf "%d", $1 * 1000 }' /proc/uptime
}
