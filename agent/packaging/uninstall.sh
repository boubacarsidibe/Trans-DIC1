#!/bin/sh
# Désinstalle l'agent Trans-DIC1. --purge supprime aussi la configuration, le jeton et le compte.
#   sudo ./uninstall.sh [--purge]

set -eu

[ "$(id -u)" = "0" ] || { echo "ERREUR : à lancer en root (sudo)" >&2; exit 1; }
purge="${1:-}"

if command -v systemctl >/dev/null 2>&1 && [ -f /etc/systemd/system/trans-dic1-agent.service ]; then
  systemctl disable --now trans-dic1-agent >/dev/null 2>&1 || true
  rm -f /etc/systemd/system/trans-dic1-agent.service
  systemctl daemon-reload
fi
rm -rf /opt/trans-dic1-agent

if [ "$purge" = "--purge" ]; then
  rm -rf /etc/trans-dic1-agent
  id trans-dic1-agent >/dev/null 2>&1 && userdel trans-dic1-agent
  echo "trans-dic1-agent : désinstallé, configuration et jeton supprimés"
  echo "Pensez à révoquer l'agent dans la plateforme (fiche de l'équipement)."
else
  echo "trans-dic1-agent : désinstallé (configuration conservée dans /etc/trans-dic1-agent)"
fi
