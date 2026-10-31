#!/bin/sh
# Tests de l'agent avec Docker seul : Python 3.8 (version minimale visée) puis 3.12 avec mesure de
# la couverture (80 % des lignes au minimum, comme le backend).
# Usage : sh agent/run-tests.sh   (depuis n'importe quel dossier)

set -eu
cd "$(dirname "$0")" || exit 2
src="$(pwd)"
# Git Bash sous Windows : chemin au format Docker et pas de conversion des chemins du conteneur.
command -v cygpath >/dev/null 2>&1 && src="$(cygpath -m "$src")"
export MSYS_NO_PATHCONV=1

run() {
  docker run --rm -e PYTHONDONTWRITEBYTECODE=1 -v "$src:/src:ro" -w /src --user 1000:1000 "$@"
}

echo "== Python 3.8"
run python:3.8-alpine python -m unittest discover -s tests -t .

echo "== Python 3.12 et couverture"
run -e HOME=/tmp python:3.12-alpine sh -ec '
  pip install --quiet --disable-pip-version-check --user coverage==7.6.1
  python -m coverage run --data-file=/tmp/.coverage --branch -m unittest discover -s tests -t .
  python -m coverage report --data-file=/tmp/.coverage --include="trans_dic1_agent/*" --show-missing --fail-under=80
'
