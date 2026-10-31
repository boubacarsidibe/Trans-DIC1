#!/bin/sh
# Rétention : supprime les sauvegardes plus anciennes que BACKUP_RETENTION_DAYS jours, avec leur
# empreinte et leur manifeste. La sauvegarde la plus récente est toujours conservée, même ancienne :
# une panne prolongée de la sauvegarde ne doit pas effacer la dernière copie disponible.

. "$(dirname "$0")/common.sh"

days="${BACKUP_RETENTION_DAYS:-14}"
case "$days" in
  '' | *[!0-9]* | 0) fail "BACKUP_RETENTION_DAYS doit être un nombre de jours supérieur à 0" ;;
esac

latest="$(latest_backup)"
removed=0
for file in $(find "$BACKUP_DIR" -maxdepth 1 -type f -name "${BACKUP_PREFIX}-*.dump.enc" -mtime +"$((days - 1))" | sort); do
  [ "$file" = "$latest" ] && continue
  rm -f "$file" "$file.sha256" "${file%.dump.enc}.json"
  removed=$((removed + 1))
done
kept="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name "${BACKUP_PREFIX}-*.dump.enc" | wc -l | tr -d ' ')"
log "rétention de $days jours : $removed sauvegarde(s) supprimée(s), $kept conservée(s)"
