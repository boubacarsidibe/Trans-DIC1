#!/bin/sh
# Déclencheur « à chaque fusion sur develop » (OPS-01) : regarde si develop a avancé sur le dépôt
# distant et, si oui, lance la chaîne de livraison sur ce commit. Prévu pour un minuteur systemd
# (deploy/systemd/trans-dic1-pipeline.timer) sur le serveur de staging.
# Un verrou empêche deux exécutions simultanées.

set -eu
cd "$(dirname "$0")/../.." || exit 2

lock="deploy/.pipeline.lock"
mkdir "$lock" 2>/dev/null || { echo "Chaîne déjà en cours : rien à faire."; exit 0; }
trap 'rmdir "$lock"' EXIT

git fetch --quiet origin develop
remote="$(git rev-parse origin/develop)"
last="$(cat deploy/.pipeline-last 2>/dev/null || true)"
if [ "$remote" = "$last" ]; then
  exit 0
fi

git checkout --quiet --detach "$remote"
if scripts/pipeline/pipeline.sh; then
  echo "$remote" > deploy/.pipeline-last
else
  # Le commit est noté même en échec : on ne relance pas en boucle une version cassée.
  echo "$remote" > deploy/.pipeline-last
  exit 1
fi
