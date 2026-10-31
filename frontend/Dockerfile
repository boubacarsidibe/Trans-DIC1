# syntax=docker/dockerfile:1

# --- Étape 1 : compilation ---------------------------------------------------
FROM node:24-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci

# L'URL de l'API est figée à la compilation par Vite.
ARG VITE_API_BASE_URL=http://localhost:8080/api/v1
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL

COPY . .
RUN npm run build

# --- Étape 2 : exécution -----------------------------------------------------
# Image nginx sans privilèges : écoute sur 8080 et s'exécute sans root.
FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 8080

# 127.0.0.1 et non localhost : dans le conteneur, localhost désigne d'abord ::1, où nginx n'écoute pas.
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/ >/dev/null || exit 1
