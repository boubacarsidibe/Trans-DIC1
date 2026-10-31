# syntax=docker/dockerfile:1

# Reverse proxy de production (US23) : nginx sans privilèges (uid 101), écoute sur 8080 (HTTP) et
# 8443 (HTTPS). Le certificat est monté au démarrage, jamais copié dans l'image.
FROM nginxinc/nginx-unprivileged:1.27-alpine

COPY templates/ /etc/nginx/templates/

EXPOSE 8080 8443

# 127.0.0.1 et non localhost : dans le conteneur, localhost désigne d'abord ::1, où nginx n'écoute pas.
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/healthz >/dev/null || exit 1
