#!/bin/sh
# Chaîne de livraison de Trans-DIC1 sans service de CI (OPS-01) : tests, construction des images,
# analyse de vulnérabilités, publication, déploiement automatique en staging.
# Lancée à chaque nouveau commit de develop par scripts/pipeline/watch-develop.sh (minuteur systemd
# sur le serveur de staging), ou à la main.
#
# Usage : scripts/pipeline/pipeline.sh [--skip-tests] [--no-deploy]
# Variables :
#   IMAGE_REGISTRY      registre des images (ex. ghcr.io/ept/trans-dic1) ; vide = images locales
#   PUSH                true pour publier dans le registre (défaut : true si IMAGE_REGISTRY est défini)
#   TRIVY_IMAGE         image de l'analyseur (défaut : aquasec/trivy:0.65.0)
#   TRIVY_SEVERITY      sévérités bloquantes (défaut : CRITICAL)
#   STAGING_ENV_FILE    fichier d'environnement du staging (défaut : .env.staging)
# Les secrets viennent des fichiers .env.* du serveur ou de l'environnement : jamais du dépôt.

set -eu
cd "$(dirname "$0")/../.." || exit 2

skip_tests=false
deploy=true
for arg in "$@"; do
  case "$arg" in
    --skip-tests) skip_tests=true ;;
    --no-deploy) deploy=false ;;
    *) echo "Option inconnue : $arg" >&2; exit 2 ;;
  esac
done

tag="$(git rev-parse --short=12 HEAD)"
registry="${IMAGE_REGISTRY:-trans-dic1}"
push="${PUSH:-$([ -n "${IMAGE_REGISTRY:-}" ] && echo true || echo false)}"
trivy_image="${TRIVY_IMAGE:-aquasec/trivy:0.65.0}"
severity="${TRIVY_SEVERITY:-CRITICAL}"
staging_env="${STAGING_ENV_FILE:-.env.staging}"
report_dir="deploy/reports"
mkdir -p "$report_dir"
report="$report_dir/pipeline-$tag.log"

log() { printf '%s [pipeline %s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$tag" "$*" | tee -a "$report"; }
fail() { log "ÉCHEC : $*"; exit 1; }

log "début (registre : $registry, publication : $push)"

# 1. Tests : la même série que celle exigée avant chaque Pull Request.
if [ "$skip_tests" = false ]; then
  log "tests backend"
  (cd backend && ./mvnw -q -B verify) >>"$report" 2>&1 || fail "tests backend"
  log "tests frontend"
  (cd frontend && npm ci --silent && npm run --silent lint && npx vitest run && npm run --silent build) \
    >>"$report" 2>&1 || fail "tests frontend"
else
  log "tests sautés (--skip-tests)"
fi

# 2. Construction des images, étiquetées par le commit : un artefact immuable par version.
log "construction des images"
IMAGE_REGISTRY="$registry" IMAGE_TAG="$tag" \
  docker compose -f docker-compose.prod.yml --env-file deploy/pipeline.env build \
  >>"$report" 2>&1 || fail "construction des images"

# 3. Analyse : une faille de sévérité bloquante (corrigeable) arrête la livraison.
for service in backend frontend proxy backup; do
  image="$registry/$service:$tag"
  log "analyse de $image ($severity)"
  docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v trans-dic1-trivy-cache:/root/.cache \
    "$trivy_image" image --quiet --no-progress --ignore-unfixed --severity "$severity" --exit-code 1 \
    "$image" >>"$report" 2>&1 || fail "faille $severity dans $image (détail dans $report)"
done

# 4. Publication.
if [ "$push" = true ]; then
  for service in backend frontend proxy backup; do
    log "publication de $registry/$service:$tag"
    docker push "$registry/$service:$tag" >>"$report" 2>&1 || fail "publication de $service"
  done
fi

# 5. Déploiement automatique en staging (retour arrière automatique en cas d'échec : deploy.sh).
if [ "$deploy" = true ]; then
  [ -f "$staging_env" ] || fail "fichier d'environnement du staging introuvable : $staging_env"
  log "déploiement en staging"
  ENV_FILE="$staging_env" DEPLOY_STATE_FILE="deploy/.staging-tag" deploy/deploy.sh "$tag" --local \
    >>"$report" 2>&1 || fail "déploiement en staging (retour arrière tenté, voir $report)"
  echo "$tag" >> deploy/.staging-validated
  log "version $tag en service en staging ; promotion : scripts/pipeline/promote.sh $tag"
fi

log "terminé"
