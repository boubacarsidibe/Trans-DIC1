#!/bin/sh
# Point d'entrée du conteneur de sauvegarde.
#   (sans argument) | schedule : sauvegarde au démarrage puis toutes les BACKUP_INTERVAL_SECONDS,
#                                chaque sauvegarde étant suivie d'une restauration de contrôle.
#   backup | restore <fichier|latest> <base> | restore-check | retention : exécution ponctuelle.

. "$(dirname "$0")/common.sh"

scripts="$(dirname "$0")"
command="${1:-schedule}"
[ $# -gt 0 ] && shift

case "$command" in
  backup) exec "$scripts/backup.sh" ;;
  restore) exec "$scripts/restore.sh" "$@" ;;
  restore-check) exec "$scripts/restore-check.sh" ;;
  retention) exec "$scripts/retention.sh" ;;
  schedule) ;;
  *) fail "commande inconnue : $command (backup, restore, restore-check, retention, schedule)" ;;
esac

interval="${BACKUP_INTERVAL_SECONDS:-86400}"
case "$interval" in
  '' | *[!0-9]*) fail "BACKUP_INTERVAL_SECONDS doit être un nombre de secondes" ;;
esac
log "planification : une sauvegarde toutes les $interval s, rétention ${BACKUP_RETENTION_DAYS:-14} jours"

while true; do
  # Un échec est journalisé et tracé, puis la boucle continue : la prochaine tentative a lieu à
  # l'échéance suivante. Le healthcheck signale une sauvegarde trop ancienne.
  if "$scripts/backup.sh"; then
    touch "$BACKUP_DIR/.last-backup-success"
    if [ "${BACKUP_RESTORE_CHECK:-true}" = "true" ]; then
      "$scripts/restore-check.sh" || log "la restauration de contrôle a échoué : voir restore-checks.log"
    fi
  fi
  sleep "$interval"
done
