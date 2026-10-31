# syntax=docker/dockerfile:1

# Service de sauvegarde PostgreSQL (OPS-02) : pg_dump, pg_restore et psql de la même version
# majeure que la base (16), openssl pour le chiffrement. Aucun secret dans l'image : la connexion
# et la clé de chiffrement sont fournies par l'environnement au démarrage.
FROM postgres:16-alpine

RUN apk add --no-cache openssl \
    && mkdir -p /backups \
    && chown postgres:postgres /backups

COPY --chmod=0555 scripts/ /opt/backup/

# Utilisateur non root fourni par l'image (uid 70).
USER postgres
ENV BACKUP_DIR=/backups
VOLUME ["/backups"]

HEALTHCHECK --interval=5m --timeout=10s --start-period=10m --retries=1 \
  CMD ["/opt/backup/healthcheck.sh"]

ENTRYPOINT ["/opt/backup/entrypoint.sh"]
CMD ["schedule"]
