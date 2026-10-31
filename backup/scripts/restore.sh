#!/bin/sh
# Restaure une sauvegarde chiffrée dans une base PostgreSQL.
# Usage : restore.sh <fichier.dump.enc | latest> <base_cible>
# La base cible est créée si elle n'existe pas. Une base qui contient déjà des tables n'est
# écrasée que si RESTORE_OVERWRITE=yes : on ne remplace jamais des données par erreur.
# La restauration se fait en une seule transaction : en cas d'erreur, la base reste inchangée.

. "$(dirname "$0")/common.sh"
# pipefail est pris en charge par le shell de l'image (busybox ash).
# shellcheck disable=SC3040
set -o pipefail

[ $# -eq 2 ] || fail "usage : restore.sh <fichier.dump.enc | latest> <base_cible>"
setup_pg_env
check_encryption_key

file="$1"
target_db="$2"
[ "$file" = "latest" ] && file="$(latest_backup)"
[ -f "$file" ] || fail "sauvegarde introuvable : ${1}"
case "$target_db" in
  *[!a-zA-Z0-9_]* | '') fail "nom de base cible invalide (lettres, chiffres et _ uniquement)" ;;
esac
# PostgreSQL limite les noms à 63 caractères ; les suffixes _restauration et _avant_<date> s'ajoutent.
[ "${#target_db}" -le 40 ] || fail "nom de base cible trop long (40 caractères au plus)"

# Intégrité du fichier stocké avant tout déchiffrement.
[ -f "$file.sha256" ] || fail "empreinte absente : $(basename "$file").sha256"
(cd "$(dirname "$file")" && sha256sum -c -s "$(basename "$file").sha256") \
  || fail "empreinte SHA-256 invalide : fichier corrompu ou modifié"

db_exists() {
  [ "$(psql -X -At -d postgres -c "SELECT 1 FROM pg_database WHERE datname = '$1'")" = "1" ]
}

# Sans connexion active, une base peut être renommée ; l'application doit être arrêtée avant.
rename_db() {
  psql -X -q -d postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$1'" >/dev/null
  psql -X -q -d postgres -c "ALTER DATABASE \"$1\" RENAME TO \"$2\"" >/dev/null
}

# Écraser une base existante : on restaure dans une base temporaire, puis on échange les noms.
# pg_restore --clean ne convient pas (il échoue sur les tables partitionnées de metric), et
# l'ancienne base est conservée sous un autre nom pour pouvoir revenir en arrière.
restore_into="$target_db"
if db_exists "$target_db"; then
  tables="$(psql -X -At -d "$target_db" -c "SELECT count(*) FROM pg_tables WHERE schemaname = 'public'")"
  if [ "$tables" != "0" ]; then
    [ "${RESTORE_OVERWRITE:-no}" = "yes" ] \
      || fail "la base $target_db contient $tables table(s) : relancez avec RESTORE_OVERWRITE=yes pour l'écraser"
    restore_into="${target_db}_restauration"
    psql -X -q -d postgres -c "DROP DATABASE IF EXISTS \"$restore_into\" WITH (FORCE)" >/dev/null
    psql -X -q -d postgres -c "CREATE DATABASE \"$restore_into\"" >/dev/null
    log "écrasement demandé : restauration préalable dans $restore_into"
  fi
else
  psql -X -q -d postgres -c "CREATE DATABASE \"$target_db\"" >/dev/null
  log "base $target_db créée"
fi

log "restauration de $(basename "$file") dans $restore_into"
started="$(date -u +%s)"
decrypt -in "$file" | pg_restore --dbname="$restore_into" --no-owner --no-privileges \
  --single-transaction --exit-on-error \
  || fail "restauration échouée (clé incorrecte, archive invalide ou erreur SQL) : base $target_db inchangée"

if [ "$restore_into" != "$target_db" ]; then
  previous="${target_db}_avant_$(date -u +%Y%m%d%H%M%S)"
  rename_db "$target_db" "$previous"
  rename_db "$restore_into" "$target_db"
  log "ancienne base conservée sous le nom $previous (à supprimer après validation)"
fi
log "restauration terminée en $(($(date -u +%s) - started)) s"
