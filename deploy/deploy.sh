#!/bin/sh
# Déploie une version de Trans-DIC1 et revient seul à la précédente si elle ne passe pas les
# contrôles (US23). À lancer sur le serveur, depuis une copie du dépôt à la version voulue.
# Usage : deploy/deploy.sh <étiquette> [--build | --local]
#   <étiquette> : version des images (ex. 1.4.0 ou le hash court du commit)
#   --build     : construit les images sur place au lieu de les télécharger du registre
#   --local     : utilise des images déjà présentes sur le serveur (ex. chargées avec docker load)
# Variables : ENV_FILE (.env.prod par défaut), DEPLOY_WAIT_SECONDS (600), DEPLOY_STATE_FILE.

set -eu
cd "$(dirname "$0")/.." || exit 2

[ $# -ge 1 ] || { echo "Usage : deploy/deploy.sh <étiquette> [--build | --local]" >&2; exit 2; }
tag="$1"
build="${2:-}"
ENV_FILE="${ENV_FILE:-.env.prod}"
wait_seconds="${DEPLOY_WAIT_SECONDS:-600}"
state="${DEPLOY_STATE_FILE:-deploy/.deployed-tag}"
[ -f "$ENV_FILE" ] || { echo "Fichier d'environnement introuvable : $ENV_FILE" >&2; exit 2; }
case "$tag" in *[!A-Za-z0-9._-]* | '') echo "Étiquette invalide : $tag" >&2; exit 2 ;; esac

compose() { docker compose -f docker-compose.prod.yml --env-file "$ENV_FILE" "$@"; }
log() { printf '%s [deploy] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

start() {
  IMAGE_TAG="$1" compose up -d --wait --wait-timeout "$wait_seconds" proxy frontend backend postgres backup
}

previous="$(cat "$state" 2>/dev/null || true)"
log "version demandée : $tag ; version en service : ${previous:-aucune}"

# 1. Sauvegarde avant tout changement : les migrations Flyway ne s'annulent pas.
if [ -n "$previous" ] && [ -n "$(compose ps -q postgres 2>/dev/null)" ]; then
  log "sauvegarde avant déploiement"
  IMAGE_TAG="$previous" compose run --rm backup backup
fi

# 2. Images de la nouvelle version.
if [ "$build" = "--build" ]; then
  IMAGE_TAG="$tag" compose build
elif [ "$build" = "--local" ]; then
  log "images locales utilisées"
else
  IMAGE_TAG="$tag" compose pull proxy frontend backend backup
fi

# 3. Démarrage, attente des healthchecks, contrôle par le proxy ; retour arrière sinon.
if start "$tag" && deploy/smoke-test.sh; then
  echo "$tag" > "$state"
  log "version $tag en service"
  exit 0
fi

log "ÉCHEC de la version $tag"
if [ -z "$previous" ]; then
  log "aucune version précédente : rien à rétablir, voir docker compose logs"
  exit 1
fi
log "retour à la version $previous"
if start "$previous" && deploy/smoke-test.sh; then
  log "version $previous rétablie ; les données n'ont pas été restaurées (voir le runbook si une migration pose problème)"
else
  log "ÉCHEC du retour arrière : intervention manuelle, voir docs/devops/deployment.md"
fi
exit 1
