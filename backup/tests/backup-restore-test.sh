#!/usr/bin/env bash
# Les vérifications « condition && pass || ko » sont voulues : pass et ko ne peuvent pas échouer.
# shellcheck disable=SC2015
# Test de bout en bout du service de sauvegarde (OPS-02), avec Docker uniquement :
#   1. une base source reçoit toutes les migrations Flyway du backend et des données fictives ;
#   2. le conteneur de sauvegarde produit un dump chiffré, son empreinte et son manifeste ;
#   3. la sauvegarde est restaurée sur un serveur PostgreSQL vierge et comparée table par table ;
#   4. la restauration de contrôle, la rétention et les cas d'erreur sont vérifiés.
# Usage : bash backup/tests/backup-restore-test.sh   (depuis n'importe quel dossier)
# Aucun fichier n'est écrit sur l'hôte : tout passe par un réseau et un volume Docker jetables.

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
run_id="backup-test-$$"
network="$run_id"
volume="$run_id"
image="trans-dic1-backup:test"
password="test-$(date +%s)-local-only"
key="cle-de-test-uniquement-$(date +%s)-0123456789abcdef"
failures=0

cleanup() {
  docker rm -f "$run_id-source" "$run_id-target" >/dev/null 2>&1 || true
  docker volume rm "$volume" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
}
trap cleanup EXIT

pass() { echo "OK   - $1"; }
ko() { echo "ECHEC - $1"; failures=$((failures + 1)); }

start_postgres() {
  docker run -d --name "$1" --network "$network" \
    -e POSTGRES_DB=trans_dic1 -e POSTGRES_USER=trans_dic1 -e POSTGRES_PASSWORD="$password" \
    postgres:16-alpine >/dev/null
  for _ in $(seq 1 60); do
    # pg_isready répond pendant l'initialisation : on attend une vraie requête sur la base finale.
    docker exec "$1" psql -U trans_dic1 -d trans_dic1 -Atc "SELECT 1" >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "PostgreSQL $1 ne démarre pas" >&2
  exit 1
}

sql() {
  docker exec -i "$1" psql -X -q -v ON_ERROR_STOP=1 -U trans_dic1 -d "${3:-trans_dic1}" -At -c "$2"
}

# Lance une commande du conteneur de sauvegarde contre le serveur $1.
backup_run() {
  local host="$1"
  shift
  docker run --rm --network "$network" -v "$volume:/backups" \
    -e DATABASE_HOST="$host" -e DATABASE_NAME=trans_dic1 -e DATABASE_USER=trans_dic1 \
    -e DATABASE_PASSWORD="$password" -e BACKUP_ENCRYPTION_KEY="$key" \
    "${BACKUP_EXTRA_ENV[@]}" "$image" "$@"
}

in_volume() {
  docker run --rm -v "$volume:/backups" --entrypoint sh "$image" -c "$1"
}

# Empreinte de chaque table : nombre de lignes et MD5 de toutes les lignes triées.
fingerprint() {
  local tables
  tables="$(sql "$1" "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename" "${2:-trans_dic1}")"
  for table in $tables; do
    printf '%s %s\n' "$table" "$(sql "$1" "SELECT count(*) || ' ' || md5(coalesce(string_agg(t::text, '|' ORDER BY t::text), '')) FROM \"$table\" t" "${2:-trans_dic1}")"
  done
}

BACKUP_EXTRA_ENV=()
echo "Construction de l'image de sauvegarde"
docker build -q -t "$image" "$root/backup" >/dev/null
docker network create "$network" >/dev/null
docker volume create "$volume" >/dev/null
start_postgres "$run_id-source"

echo "Schéma réel : migrations Flyway V1 à Vn, puis données fictives"
sql "$run_id-source" "CREATE TABLE flyway_schema_history (installed_rank INT PRIMARY KEY, version VARCHAR(50), description VARCHAR(200), success BOOLEAN NOT NULL)"
rank=0
last_version=""
for migration in $(ls "$root"/backend/src/main/resources/db/migration/V*__*.sql | sed 's#.*/V\([0-9]*\)__.*#\1 &#' | sort -n | cut -d' ' -f2); do
  docker exec -i "$run_id-source" psql -X -q -v ON_ERROR_STOP=1 -U trans_dic1 -d trans_dic1 < "$migration" >/dev/null
  rank=$((rank + 1))
  last_version="$(basename "$migration" | sed 's/^V\([0-9]*\)__.*/\1/')"
  sql "$run_id-source" "INSERT INTO flyway_schema_history VALUES ($rank, '$last_version', 'migration', true)"
done
[ "$rank" -gt 0 ] && pass "$rank migrations appliquées (dernière : V$last_version)"
sql "$run_id-source" "CREATE TABLE backup_test_data (id INT PRIMARY KEY, label TEXT NOT NULL, payload BYTEA)"
sql "$run_id-source" "INSERT INTO backup_test_data SELECT g, 'Équipement fictif n°' || g || ' — 192.0.2.' || (g % 254 + 1), decode(md5(g::text), 'hex') FROM generate_series(1, 5000) g"
before="$(fingerprint "$run_id-source")"

echo "Sauvegarde"
backup_run "$run_id-source" backup >/dev/null
file="$(in_volume 'ls /backups/*.dump.enc' | head -n 1)"
[ -n "$file" ] && pass "sauvegarde créée : $(basename "$file")" || ko "aucune sauvegarde créée"
in_volume "cd /backups && sha256sum -c -s *.sha256" && pass "empreinte SHA-256 valide" || ko "empreinte invalide"
in_volume "grep -q 'Équipement fictif' /backups/*.dump.enc" && ko "données lisibles en clair dans la sauvegarde" || pass "sauvegarde chiffrée (aucune donnée en clair)"
in_volume "head -c 8 /backups/*.dump.enc" | grep -q '^Salted__' && pass "format openssl salé" || ko "en-tête de chiffrement absent"
manifest="$(in_volume 'cat /backups/*.json')"
echo "$manifest" | grep -q "\"schemaVersion\":\"$last_version\"" && pass "manifeste : schéma V$last_version" || ko "manifeste incorrect : $manifest"
echo "$manifest" | grep -qiE 'password|key|secret' && ko "le manifeste contient un terme sensible" || pass "manifeste sans secret"

echo "Restauration sur un serveur PostgreSQL vierge"
start_postgres "$run_id-target"
sql "$run_id-target" "SELECT count(*) FROM pg_tables WHERE schemaname = 'public'" | grep -qx 0 && pass "serveur cible vierge"
started="$(date +%s)"
backup_run "$run_id-target" restore latest trans_dic1_restored >/dev/null
duration=$(($(date +%s) - started))
after="$(fingerprint "$run_id-target" trans_dic1_restored)"
if [ "$before" = "$after" ]; then
  pass "données identiques après restauration ($(echo "$after" | wc -l | tr -d ' ') tables comparées, $duration s)"
else
  ko "données différentes après restauration"
  diff <(echo "$before") <(echo "$after") || true
fi

echo "Garde-fous de la restauration"
if backup_run "$run_id-target" restore latest trans_dic1_restored >/dev/null 2>&1; then
  ko "une base non vide a été écrasée sans RESTORE_OVERWRITE=yes"
else
  pass "base non vide protégée sans RESTORE_OVERWRITE=yes"
fi
BACKUP_EXTRA_ENV=(-e RESTORE_OVERWRITE=yes)
sql "$run_id-target" "DELETE FROM backup_test_data WHERE id > 10" trans_dic1_restored
if backup_run "$run_id-target" restore latest trans_dic1_restored >/dev/null; then
  [ "$(fingerprint "$run_id-target" trans_dic1_restored)" = "$before" ] \
    && pass "écrasement explicite : données de la sauvegarde rétablies" || ko "données incorrectes après écrasement"
  sql "$run_id-target" "SELECT datname FROM pg_database WHERE datname LIKE 'trans_dic1_restored_avant_%'" | grep -q . \
    && pass "ancienne base conservée pour revenir en arrière" || ko "ancienne base non conservée"
else
  ko "écrasement explicite refusé"
fi
BACKUP_EXTRA_ENV=()
if docker run --rm --network "$network" -v "$volume:/backups" -e DATABASE_HOST="$run_id-target" \
  -e DATABASE_NAME=trans_dic1 -e DATABASE_USER=trans_dic1 -e DATABASE_PASSWORD="$password" \
  -e BACKUP_ENCRYPTION_KEY="mauvaise-cle-de-test-0123456789abcdef" "$image" restore latest trans_dic1_wrong_key >/dev/null 2>&1; then
  ko "restauration acceptée avec une mauvaise clé"
else
  pass "mauvaise clé refusée"
fi
sql "$run_id-target" "SELECT count(*) FROM pg_tables WHERE schemaname = 'public'" trans_dic1_wrong_key | grep -qx 0 \
  && pass "échec sans effet partiel (transaction unique)" || ko "restauration partielle après échec"
in_volume "printf 'X' | dd of=\$(ls /backups/*.dump.enc) bs=1 seek=100 conv=notrunc 2>/dev/null"
if backup_run "$run_id-target" restore latest trans_dic1_tampered >/dev/null 2>&1; then
  ko "fichier modifié accepté"
else
  pass "fichier modifié détecté par l'empreinte"
fi

echo "Restauration de contrôle et rétention"
backup_run "$run_id-source" backup >/dev/null
backup_run "$run_id-source" restore-check >/dev/null && pass "restauration de contrôle réussie" || ko "restauration de contrôle en échec"
check="$(in_volume 'cat /backups/last-restore-check.json')"
echo "$check" | grep -q '"status":"SUCCESS"' && echo "$check" | grep -q '"withinRto":true' \
  && pass "résultat tracé avec durée et RTO : $check" || ko "résultat de contrôle inattendu : $check"
sql "$run_id-source" "SELECT name || ':' || last_status FROM job_run WHERE name LIKE 'backup.%' ORDER BY name" | tr '\n' ' ' \
  | grep -q "backup.postgres:SUCCESS backup.restore-check:SUCCESS" && pass "résultats tracés dans job_run" || ko "job_run non renseigné"
sql "$run_id-source" "SELECT count(*) FROM pg_database WHERE datname = 'trans_dic1_restore_check'" | grep -qx 0 \
  && pass "base de contrôle supprimée après usage" || ko "base de contrôle laissée en place"
BACKUP_EXTRA_ENV=(-e RTO_SECONDS=0)
if backup_run "$run_id-source" restore-check >/dev/null 2>&1; then ko "RTO dépassé non signalé"; else pass "dépassement du RTO signalé en échec"; fi
BACKUP_EXTRA_ENV=()

in_volume "rm -f /backups/*.dump.enc /backups/*.sha256 /backups/*.json \
  && for d in 20200101T000000Z 20200102T000000Z; do f=/backups/trans_dic1-\$d.dump.enc; echo x > \$f; touch -d '2020-01-01 00:00:00' \$f; done \
  && echo x > /backups/trans_dic1-29990101T000000Z.dump.enc"
backup_run "$run_id-source" retention >/dev/null
remaining="$(in_volume 'ls /backups/*.dump.enc' | xargs -n1 basename | tr '\n' ' ')"
[ "$remaining" = "trans_dic1-29990101T000000Z.dump.enc " ] && pass "rétention : anciennes sauvegardes supprimées" || ko "rétention incorrecte : $remaining"
in_volume "rm -f /backups/trans_dic1-2999*.dump.enc && echo x > /backups/trans_dic1-20200101T000000Z.dump.enc && touch -d '2020-01-01 00:00:00' /backups/trans_dic1-20200101T000000Z.dump.enc"
backup_run "$run_id-source" retention >/dev/null
in_volume 'ls /backups/*.dump.enc' | grep -q 20200101 && pass "rétention : la dernière sauvegarde est toujours conservée" || ko "la dernière sauvegarde a été supprimée"

echo
if [ "$failures" -eq 0 ]; then
  echo "Toutes les vérifications de sauvegarde et de restauration sont passées."
else
  echo "$failures vérification(s) en échec."
  exit 1
fi
