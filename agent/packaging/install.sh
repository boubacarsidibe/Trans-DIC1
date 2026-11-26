#!/bin/sh
# Installe ou met à jour l'agent Trans-DIC1 sur un serveur Linux (à lancer en root, depuis le
# dossier décompressé du paquet).
#   sudo ./install.sh --server-url https://supervision.example --token-file /chemin/jeton
#   sudo ./install.sh --server-url https://supervision.example --token-stdin < /chemin/jeton
#   sudo ./install.sh --server-url https://supervision.example --enroll-token-file /chemin/jeton-inscription
# Avec --enroll-token-file (ADR 0054), l'agent s'inscrit seul au démarrage avec un jeton partagé par
# tout le parc, reçoit son jeton personnel et supprime le jeton d'inscription.
# Mise à jour : relancer sans option ; configuration et jeton existants sont conservés.
# Options : --no-systemd (installation sans service, ex. conteneur de test), --no-start.
# Le jeton n'est jamais affiché ni écrit ailleurs que dans /etc/trans-dic1-agent/token (600).

set -eu
cd "$(dirname "$0")" || exit 2

USER_NAME=trans-dic1-agent
APP_DIR=/opt/trans-dic1-agent
CONF_DIR=/etc/trans-dic1-agent
# Dossier d'état, écrit par l'agent lui-même : le jeton reçu à l'inscription y est déposé.
STATE_DIR=/var/lib/trans-dic1-agent
UNIT=/etc/systemd/system/trans-dic1-agent.service
server_url=""
token_source=""
enroll_source=""
systemd=yes
start=yes

fail() { echo "ERREUR : $*" >&2; exit 1; }
info() { echo "trans-dic1-agent : $*"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --server-url) server_url="${2:-}"; shift 2 ;;
    --token-file) token_source="${2:-}"; shift 2 ;;
    --token-stdin) token_source="-"; shift ;;
    --enroll-token-file) enroll_source="${2:-}"; shift 2 ;;
    --no-systemd) systemd=no; shift ;;
    --no-start) start=no; shift ;;
    *) fail "option inconnue : $1" ;;
  esac
done

[ -n "$token_source" ] && [ -n "$enroll_source" ]   && fail "--token-file et --enroll-token-file s'excluent : un jeton personnel ou un jeton d'inscription"
[ "$(id -u)" = "0" ] || fail "à lancer en root (sudo)"
command -v python3 >/dev/null 2>&1 || fail "python3 est requis (ex. apt install python3)"
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' \
  || fail "Python 3.8 ou plus récent est requis"
case "$server_url" in
  '' | https://*) ;;
  *) fail "--server-url doit commencer par https://" ;;
esac

# 1. Compte système sans shell ni dossier personnel.
if ! id "$USER_NAME" >/dev/null 2>&1; then
  nologin="$(command -v nologin || echo /usr/sbin/nologin)"
  useradd --system --no-create-home --home-dir "$APP_DIR" --shell "$nologin" "$USER_NAME"
  info "compte $USER_NAME créé"
fi

# 2. Code : propriété de root, lisible par tous, modifiable par personne d'autre.
rm -rf "$APP_DIR.new"
mkdir -p "$APP_DIR.new"
cp -R trans_dic1_agent "$APP_DIR.new/"
[ -f README.md ] && cp README.md "$APP_DIR.new/"
find "$APP_DIR.new" -name '__pycache__' -prune -exec rm -rf {} +
chown -R root:root "$APP_DIR.new"
chmod -R u=rwX,go=rX "$APP_DIR.new"
rm -rf "$APP_DIR"
mv "$APP_DIR.new" "$APP_DIR"

# 3. Configuration : créée une fois, jamais écrasée par une mise à jour.
install -d -m 750 -o root -g "$USER_NAME" "$CONF_DIR"
if [ ! -f "$CONF_DIR/agent.conf" ]; then
  [ -n "$server_url" ] || fail "--server-url est obligatoire à la première installation"
  sed "s#^server_url = .*#server_url = $server_url#" agent.conf.example > "$CONF_DIR/agent.conf"
  info "configuration créée : $CONF_DIR/agent.conf"
elif [ -n "$server_url" ]; then
  sed -i "s#^server_url = .*#server_url = $server_url#" "$CONF_DIR/agent.conf"
fi
chown root:"$USER_NAME" "$CONF_DIR/agent.conf"
chmod 640 "$CONF_DIR/agent.conf"

# 4. Jeton : lisible par le seul compte de l'agent.
install -d -m 700 -o "$USER_NAME" -g "$USER_NAME" "$STATE_DIR"
conf_value() { sed -n "s/^$1 *= *//p" "$CONF_DIR/agent.conf" | tail -n 1; }
set_conf() {
  if grep -q "^$1 *=" "$CONF_DIR/agent.conf"; then
    sed -i "s#^$1 *=.*#$1 = $2#" "$CONF_DIR/agent.conf"
  else
    sed -i "/^token_file *=/a $1 = $2" "$CONF_DIR/agent.conf"
  fi
}
token_path="$(conf_value token_file)"
[ -n "$token_path" ] || token_path="$CONF_DIR/token"

if [ -n "$enroll_source" ]; then
  [ -f "$enroll_source" ] || fail "fichier de jeton d'inscription introuvable : $enroll_source"
  if [ -s "$token_path" ]; then
    # Relancer l'installation ne réinscrit pas une machine déjà inscrite (idempotence).
    info "jeton personnel déjà présent : jeton d'inscription ignoré"
  else
    umask 077
    tr -d ' \r\n' < "$enroll_source" > "$STATE_DIR/enroll-token"
    chown "$USER_NAME:$USER_NAME" "$STATE_DIR/enroll-token"
    chmod 600 "$STATE_DIR/enroll-token"
    token_path="$STATE_DIR/token"
    set_conf token_file "$token_path"
    set_conf enroll_token_file "$STATE_DIR/enroll-token"
    info "jeton d'inscription installé : l'agent s'inscrira au démarrage"
  fi
fi

if [ -n "$token_source" ]; then
  umask 077
  if [ "$token_source" = "-" ]; then
    tr -d ' \r\n' > "$CONF_DIR/token"
  else
    [ -f "$token_source" ] || fail "fichier de jeton introuvable : $token_source"
    tr -d ' \r\n' < "$token_source" > "$CONF_DIR/token"
  fi
  # Un jeton personnel fourni à la main remplace une inscription en cours ou passée.
  set_conf token_file "$CONF_DIR/token"
  token_path="$CONF_DIR/token"
  info "jeton installé"
fi
if [ -s "$CONF_DIR/token" ]; then
  chown "$USER_NAME:$USER_NAME" "$CONF_DIR/token"
  chmod 600 "$CONF_DIR/token"
fi
enroll_path="$(conf_value enroll_token_file)"
if [ ! -s "$token_path" ] && [ ! -s "$CONF_DIR/token" ] && { [ -z "$enroll_path" ] || [ ! -s "$enroll_path" ]; }; then
  fail "aucun jeton : utiliser --token-file, --token-stdin ou --enroll-token-file"
fi

# 5. Vérification avec le compte de l'agent, sans rien envoyer.
run_as_agent() {
  if command -v runuser >/dev/null 2>&1; then
    runuser -u "$USER_NAME" -- "$@"
  else
    su -s /bin/sh "$USER_NAME" -c "$*"
  fi
}
(cd "$APP_DIR" && run_as_agent python3 -m trans_dic1_agent --config "$CONF_DIR/agent.conf" --check) \
  || fail "la vérification de la configuration a échoué"

# 6. Service systemd.
if [ "$systemd" = "yes" ]; then
  command -v systemctl >/dev/null 2>&1 || fail "systemd absent : relancer avec --no-systemd"
  install -m 644 -o root -g root trans-dic1-agent.service "$UNIT"
  systemctl daemon-reload
  systemctl enable trans-dic1-agent >/dev/null
  if [ "$start" = "yes" ]; then
    systemctl restart trans-dic1-agent
    info "service démarré : journalctl -u trans-dic1-agent -f"
  fi
fi
info "installation terminée ($(cd "$APP_DIR" && python3 -m trans_dic1_agent --version))"
