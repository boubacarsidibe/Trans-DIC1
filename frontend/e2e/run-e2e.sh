#!/bin/sh
# Tests de bout en bout (QA-02) : démarre une pile jetable (PostgreSQL, API, interface) avec des
# secrets aléatoires, lance Playwright, puis supprime la pile et ses données.
# Usage (depuis frontend/) : sh e2e/run-e2e.sh [options Playwright, ex. e2e/alert.spec.js]
#   E2E_KEEP=1                garde la pile après les tests (pour relancer ou déboguer)
#   E2E_BROWSER_CHANNEL=      utilise le Chromium de Playwright (npx playwright install chromium)
# Rapport : playwright-report/index.html ; traces, captures et vidéos des échecs : test-results/.

set -eu
cd "$(dirname "$0")/.." || exit 2

secret() { node -e "process.stdout.write(require('crypto').randomBytes(24).toString('hex'))"; }
export E2E_DATABASE_PASSWORD="${E2E_DATABASE_PASSWORD:-$(secret)}"
export E2E_JWT_SECRET="${E2E_JWT_SECRET:-$(secret)}"
export E2E_PASSWORD="${E2E_PASSWORD:-E2e-$(secret)}"
compose() { docker compose -f e2e/compose.e2e.yml "$@"; }

echo "Démarrage de la pile de test (construction des images si besoin)..."
compose up -d --build --wait --wait-timeout 600

status=0
npx playwright test "$@" || status=$?

if [ "${E2E_KEEP:-0}" = "1" ]; then
  echo "Pile conservée (E2E_KEEP=1) : interface http://localhost:15173, API http://localhost:18080."
  echo "Pour relancer avec les mêmes comptes, réutiliser E2E_PASSWORD, E2E_DATABASE_PASSWORD et E2E_JWT_SECRET."
else
  compose down -v >/dev/null
fi
exit "$status"
