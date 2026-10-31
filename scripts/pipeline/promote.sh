#!/bin/sh
# Passage d'une version du staging à la production, sur approbation explicite (OPS-01).
# Usage : scripts/pipeline/promote.sh <étiquette>
#   L'étiquette doit avoir été déployée avec succès en staging par pipeline.sh.
#   L'approbation est demandée au terminal ; en automatisation, APPROVED_BY=<nom> la fournit.
# Variables : PROD_ENV_FILE (défaut .env.prod).
# Chaque approbation est consignée dans deploy/approvals.log (qui, quoi, quand).

set -eu
cd "$(dirname "$0")/../.." || exit 2

[ $# -eq 1 ] || { echo "Usage : scripts/pipeline/promote.sh <étiquette>" >&2; exit 2; }
tag="$1"
case "$tag" in *[!A-Za-z0-9._-]* | '') echo "Étiquette invalide : $tag" >&2; exit 2 ;; esac
prod_env="${PROD_ENV_FILE:-.env.prod}"

if ! grep -qx "$tag" deploy/.staging-validated 2>/dev/null; then
  echo "La version $tag n'a pas été validée en staging : promotion refusée." >&2
  exit 1
fi
[ -f "$prod_env" ] || { echo "Fichier d'environnement de production introuvable : $prod_env" >&2; exit 2; }

approver="${APPROVED_BY:-}"
if [ -z "$approver" ]; then
  printf 'Passer la version %s en PRODUCTION ? Tapez votre nom pour approuver : ' "$tag"
  read -r approver
fi
[ -n "$approver" ] || { echo "Aucune approbation : rien n'est fait." >&2; exit 1; }

printf '%s\t%s\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$tag" "$approver" >> deploy/approvals.log
echo "Approuvée par $approver : déploiement de $tag en production."
ENV_FILE="$prod_env" deploy/deploy.sh "$tag" --local
