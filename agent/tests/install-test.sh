#!/bin/sh
# Test du paquet : construction, installation sur une Debian vierge (conteneur, sans systemd),
# droits des fichiers, envoi réel vers un faux serveur, mise à jour puis désinstallation.
# Usage : sh agent/tests/install-test.sh   (Docker requis)

set -eu
cd "$(dirname "$0")/.." || exit 2
sh packaging/build-package.sh >/dev/null
version="$(sed -n 's/^VERSION = "\(.*\)"/\1/p' trans_dic1_agent/__init__.py)"
src="$(pwd)"
command -v cygpath >/dev/null 2>&1 && src="$(cygpath -m "$src")"
export MSYS_NO_PATHCONV=1

docker run --rm -e VERSION="$version" -v "$src:/agent:ro" python:3.12-slim sh -eu -c '
  fail() { echo "ECHEC - $*"; exit 1; }
  ok() { echo "OK    - $*"; }
  cd /tmp && tar -xzf /agent/dist/trans-dic1-agent-$VERSION.tar.gz
  [ ! -e trans-dic1-agent-$VERSION/tests ] && ok "paquet sans tests" || fail "tests dans le paquet"
  token="tdic1_jeton-de-test-$(date +%s)-abcdefghijklmnop"
  printf "%s\n" "$token" > /tmp/jeton && chmod 600 /tmp/jeton

  ./trans-dic1-agent-$VERSION/install.sh --server-url https://supervision.test --token-file /tmp/jeton --no-systemd > /tmp/install.log
  grep -q "installation terminée" /tmp/install.log && ok "installation" || fail "installation : $(cat /tmp/install.log)"
  grep -qF "$token" /tmp/install.log && fail "jeton affiché pendant l installation" || ok "jeton jamais affiché"
  [ "$(stat -c "%a %U" /etc/trans-dic1-agent/token)" = "600 trans-dic1-agent" ] && ok "jeton : 600, compte de l agent" || fail "droits du jeton"
  [ "$(stat -c "%a %U %G" /etc/trans-dic1-agent/agent.conf)" = "640 root trans-dic1-agent" ] && ok "configuration : 640 root" || fail "droits de la configuration"
  [ "$(stat -c %U /opt/trans-dic1-agent/trans_dic1_agent/agent.py)" = "root" ] && ok "code non modifiable par l agent" || fail "propriétaire du code"
  grep -q "^server_url = https://supervision.test" /etc/trans-dic1-agent/agent.conf && ok "adresse de la plateforme configurée" || fail "server_url"
  grep -qi "token\s*=" /etc/trans-dic1-agent/agent.conf && fail "jeton dans agent.conf" || ok "aucun jeton dans agent.conf"

  python3 /agent/tests/fake_server.py 8765 "$token" > /tmp/server.log &
  sleep 1
  cd /opt/trans-dic1-agent
  runuser -u trans-dic1-agent -- env TRANS_DIC1_AGENT_SERVER_URL=http://127.0.0.1:8765 TRANS_DIC1_AGENT_ALLOW_INSECURE_HTTP=true \
    python3 -m trans_dic1_agent --config /etc/trans-dic1-agent/agent.conf --once > /tmp/agent.log
  wait
  grep -q "reçu 4 mesure(s) : availability, cpu.usage, disk.usage, memory.usage" /tmp/server.log && ok "envoi réel, compte non root : $(cat /tmp/server.log)" || fail "envoi : $(cat /tmp/agent.log /tmp/server.log)"
  grep -qF "$token" /tmp/agent.log && fail "jeton dans les journaux" || ok "jeton absent des journaux"

  cd /tmp && ./trans-dic1-agent-$VERSION/install.sh --no-systemd > /tmp/upgrade.log
  grep -q "^server_url = https://supervision.test" /etc/trans-dic1-agent/agent.conf && [ -s /etc/trans-dic1-agent/token ] \
    && ok "mise à jour : configuration et jeton conservés" || fail "mise à jour"

  ./trans-dic1-agent-$VERSION/uninstall.sh --purge > /dev/null
  [ ! -e /opt/trans-dic1-agent ] && [ ! -e /etc/trans-dic1-agent ] && ! id trans-dic1-agent >/dev/null 2>&1 \
    && ok "désinstallation complète" || fail "désinstallation"
  echo "Test du paquet réussi."
'
