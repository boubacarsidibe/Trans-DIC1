#!/bin/sh
# Contrôle d'un déploiement de production (US23), lancé sur le serveur après « up ».
# Usage : deploy/smoke-test.sh            (ENV_FILE=.env.prod par défaut)
# Vérifie le parcours réel par le reverse proxy, la santé des conteneurs, l'absence de root et
# l'absence de secret dans les images. N'affiche jamais la valeur d'un secret.
# SMOKE_INSECURE=true accepte un certificat auto-signé (test local uniquement).

# Pas de « set -e » : chaque vérification est comptée, aucune n'interrompt le script.
set -u
cd "$(dirname "$0")/.." || exit 2

ENV_FILE="${ENV_FILE:-.env.prod}"
[ -f "$ENV_FILE" ] || { echo "Fichier d'environnement introuvable : $ENV_FILE" >&2; exit 2; }
compose() { docker compose -f docker-compose.prod.yml --env-file "$ENV_FILE" "$@"; }
val() { grep -E "^$1=" "$ENV_FILE" | tail -n 1 | cut -d= -f2-; }

host="$(val PUBLIC_HOSTNAME)"
https_port="$(val HTTPS_PORT)"
http_port="$(val HTTP_PORT)"
https_port="${https_port:-443}"
http_port="${http_port:-80}"
base="https://$host:$https_port"
tls="--resolve $host:$https_port:127.0.0.1"
[ "${SMOKE_INSECURE:-false}" = "true" ] && tls="$tls -k"
failures=0

# check <libellé> <commande...> : exécute la condition et compte le résultat.
check() {
  label="$1"
  shift
  if "$@"; then echo "OK    - $label"; else echo "ECHEC - $label"; failures=$((failures + 1)); fi
}
# shellcheck disable=SC2086
get() { curl -s $tls --max-time 10 "$@"; }
status_of() { get -o /dev/null -w '%{http_code}' "$@"; }
body_contains() { get "$1" | grep -q "$2"; }

# 1. Parcours par le reverse proxy.
location="$(curl -s -D - -o /dev/null --max-time 10 -H "Host: $host" "http://127.0.0.1:$http_port/inventaire" | tr -d '\r' | grep -i '^location:' | cut -d' ' -f2)"
check "HTTP redirige vers HTTPS (${location:-aucune redirection})" [ "$location" = "https://$host/inventaire" ]
check "interface servie en HTTPS" body_contains "$base/" '<div id="root">'
hsts="$(get -D - -o /dev/null "$base/api/v1/vue-ensemble" | grep -ci '^strict-transport-security')"
check "en-tête HSTS présent une seule fois" [ "$hsts" = "1" ]
check "santé de l'API par le proxy" body_contains "$base/actuator/health" '"status":"UP"'
check "métriques non exposées à l'extérieur" [ "$(status_of "$base/actuator/prometheus")" = "404" ]
check "API protégée (401 sans jeton)" [ "$(status_of "$base/api/v1/vue-ensemble")" = "401" ]
check "réception des agents joignable et authentifiée" \
  [ "$(status_of "$base/api/v1/agent/measures" -X POST -H 'Content-Type: application/json' -d '{}')" = "401" ]

# 2. Conteneurs : santé, utilisateur, ports.
for service in proxy frontend backend postgres backup; do
  state="$(docker inspect --format '{{.State.Health.Status}}' "$(compose ps -q "$service")" 2>/dev/null || echo absent)"
  check "$service : $state" [ "$state" = "healthy" ]
done
for service in proxy frontend backend backup; do
  uid="$(compose exec -T "$service" id -u 2>/dev/null || echo 0)"
  check "$service sans root (uid $uid)" [ "$uid" != "0" ]
done
check "PostgreSQL non publié sur l'hôte" [ -z "$(docker port "$(compose ps -q postgres)")" ]

# 3. Aucun secret dans les images (variables et historique de construction).
for service in proxy frontend backend backup; do
  image="$(docker inspect --format '{{.Config.Image}}' "$(compose ps -q "$service")")"
  content="$(docker image inspect --format '{{json .Config.Env}}' "$image"; docker history --no-trunc --format '{{.CreatedBy}}' "$image")"
  leaked=0
  for name in DATABASE_PASSWORD JWT_SECRET BACKUP_ENCRYPTION_KEY METRICS_SCRAPE_TOKEN SMTP_PASSWORD BOOTSTRAP_ADMIN_PASSWORD; do
    secret="$(val "$name")"
    [ -n "$secret" ] && printf '%s' "$content" | grep -qF -- "$secret" && leaked=1
  done
  check "image $service sans secret" [ "$leaked" = "0" ]
done

echo
if [ "$failures" -eq 0 ]; then
  echo "Déploiement vérifié."
else
  echo "$failures vérification(s) en échec."
  exit 1
fi
