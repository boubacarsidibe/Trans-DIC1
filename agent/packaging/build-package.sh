#!/bin/sh
# Construit le paquet de l'agent : agent/dist/trans-dic1-agent-<version>.tar.gz et son empreinte.
# Le paquet ne contient ni test ni secret : le jeton est fourni à l'installation.
# Usage : sh agent/packaging/build-package.sh

set -eu
cd "$(dirname "$0")/.." || exit 2

version="$(sed -n 's/^VERSION = "\(.*\)"/\1/p' trans_dic1_agent/__init__.py)"
[ -n "$version" ] || { echo "version introuvable" >&2; exit 1; }
name="trans-dic1-agent-$version"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/$name" dist
cp -R trans_dic1_agent "$work/$name/"
cp packaging/install.sh packaging/uninstall.sh packaging/agent.conf.example \
  packaging/trans-dic1-agent.service README.md "$work/$name/"
find "$work/$name" -name '__pycache__' -prune -exec rm -rf {} +
chmod 755 "$work/$name/install.sh" "$work/$name/uninstall.sh"

tar -C "$work" -czf "dist/$name.tar.gz" "$name"
(cd dist && sha256sum "$name.tar.gz" > "$name.tar.gz.sha256")
echo "paquet : agent/dist/$name.tar.gz"
cat "dist/$name.tar.gz.sha256"
