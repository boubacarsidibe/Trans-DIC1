# Déploiement de production

Ce guide installe Trans-DIC1 sur un serveur Linux avec Docker, selon le diagramme cible (`docs/architecture/diagrams/07-deployment/deployment-target.mmd`). Les choix sont expliqués dans l'[ADR-0011](../adr/0011-deployment.md).

> Le serveur de staging et celui de production ne sont pas encore fournis par le CRI. La procédure ci-dessous a été exécutée de bout en bout sur un poste de développement, avec un certificat auto-signé et un nom de domaine fictif.

## Architecture déployée

```text
Navigateur / agents ── HTTPS 443 ──> proxy (Nginx, uid 101)
                                      ├── /          ──> frontend (Nginx, uid 101)
                                      ├── /api/      ──> backend (Spring Boot, uid 10001) ──> équipements, SMTP, canaux
                                      └── /actuator/health
                       réseau « back » sans accès extérieur :
                                      backend ──> postgres (PostgreSQL 16, aucun port publié)
                                      backup (uid 70) ──> postgres
```

| Service | Image | Healthcheck | Données |
| --- | --- | --- | --- |
| `proxy` | `proxy/Dockerfile` (nginx sans privilèges) | `/healthz` | certificat monté en lecture seule |
| `frontend` | `frontend/Dockerfile` (construit avec `VITE_API_BASE_URL=/api/v1`) | page d'accueil | aucune |
| `backend` | `backend/Dockerfile` (JRE 21), profil `prod`, logs JSON | `/actuator/health/readiness`, démarrage toléré jusqu'à 5 min | aucune |
| `postgres` | `postgres:16-alpine` | `pg_isready` | volume `postgres-data` |
| `backup` | `backup/Dockerfile` | âge de la dernière sauvegarde | volume `backup-data` ou `BACKUP_HOST_DIR` |

Les services applicatifs tournent sans root, avec un système de fichiers en lecture seule (`/tmp` en mémoire), sans aucune capacité Linux (`cap_drop: ALL`) et avec `no-new-privileges`. Les logs Docker tournent (5 fichiers de 10 Mo par service).

Le collecteur n'est pas un service séparé : c'est le module `collection` du backend (ADR-0006). Les agents Trans-DIC1 envoient leurs mesures à `https://<PUBLIC_HOSTNAME>/api/v1/agent/measures`, en passant par le proxy.

## Prérequis sur le serveur

- Linux avec Docker Engine 25 ou plus récent et le plugin Compose v2 ;
- un nom DNS (`PUBLIC_HOSTNAME`) et son certificat TLS fournis par le CRI ;
- les ports 80 et 443 ouverts vers le serveur ; aucun autre port n'est publié, hors interfaces de supervision limitées à `127.0.0.1` ;
- un stockage distinct monté pour les sauvegardes (`BACKUP_HOST_DIR`, voir [operations.md](operations.md#sauvegardes)).

## Installation

1. Récupérer le dépôt à la version voulue, puis créer `.env.prod` à partir de `.env.example`. Ce fichier ne doit jamais être versionné ni copié dans une image.

   ```sh
   git clone <dépôt> trans-dic1 && cd trans-dic1
   cp .env.example .env.prod && chmod 600 .env.prod
   ```

2. Renseigner dans `.env.prod` au minimum :
   - `PUBLIC_HOSTNAME` et `PUBLIC_URL` (`https://<PUBLIC_HOSTNAME>`) ;
   - `DATABASE_PASSWORD`, `JWT_SECRET`, `BACKUP_ENCRYPTION_KEY` et `METRICS_SCRAPE_TOKEN` (32 caractères aléatoires, par exemple avec `openssl rand -hex 32`) ;
   - `BOOTSTRAP_ADMIN_*` pour le premier démarrage ;
   - `SMTP_*` (serveur du CRI) et `BACKUP_HOST_DIR`.

   Conserver `BACKUP_ENCRYPTION_KEY` aussi hors du serveur.

3. Déposer le certificat dans `proxy/tls/` (ou dans `TLS_CERT_DIR`) sous les noms `tls.crt` (chaîne complète) et `tls.key`. Nginx tourne sous l'uid 101 : la clé doit lui appartenir et rester privée.

   ```sh
   sudo chown 101:101 proxy/tls/tls.key proxy/tls/tls.crt && sudo chmod 600 proxy/tls/tls.key
   ```

4. Déployer :

   ```sh
   deploy/deploy.sh 1.0.0 --build
   ```

   Sans registre d'images, `--build` construit les images sur le serveur. `--local` utilise des images déjà chargées (`docker load`). Sans option, le script télécharge les images de `IMAGE_REGISTRY` à l'étiquette donnée.

### Certificat auto-signé pour un test

Pour un test seulement (le navigateur affichera un avertissement) :

```powershell
docker run --rm -v "${PWD}/proxy/tls:/tls" alpine/openssl:3.5.4 req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=supervision.test" -addext "subjectAltName=DNS:supervision.test" -keyout /tls/tls.key -out /tls/tls.crt
docker run --rm --entrypoint chown -v "${PWD}/proxy/tls:/tls" alpine/openssl:3.5.4 101:101 /tls/tls.key /tls/tls.crt
```

Puis, dans `.env.prod`, mettre `PUBLIC_HOSTNAME=supervision.test` et `PUBLIC_URL=https://supervision.test`, et faire pointer ce nom vers le serveur (fichier `hosts` du poste de test).

## Ce que fait `deploy/deploy.sh`

1. Il lit la version en service dans `deploy/.deployed-tag`.
2. Il sauvegarde la base si une version tourne déjà (`backup backup`) : les migrations Flyway ne s'annulent pas.
3. Il construit, télécharge ou reprend les images de la nouvelle étiquette.
4. Il démarre `proxy`, `frontend`, `backend`, `postgres` et `backup`, et attend que tous soient `healthy` (10 min au plus, `DEPLOY_WAIT_SECONDS`).
5. Il lance `deploy/smoke-test.sh`, qui vérifie par le proxy :
   - la redirection HTTPS et l'en-tête HSTS ;
   - l'interface, la santé de l'API, l'API protégée et la réception des agents ;
   - les métriques invisibles depuis l'extérieur ;
   - la santé et l'utilisateur non root de chaque conteneur ;
   - PostgreSQL non publié ;
   - aucun secret de `.env.prod` dans les images.
6. En cas de succès, il enregistre la nouvelle étiquette. En cas d'échec, il redémarre l'étiquette précédente et la contrôle de la même façon.

Le contrôle peut aussi être lancé seul à tout moment : `deploy/smoke-test.sh`.

## Retour arrière

- **Automatique** : un déploiement qui échoue aux healthchecks ou au contrôle revient seul à la version précédente. Le message `version <précédente> rétablie` le confirme.
- **Manuel** (régression constatée après coup) : relancer le script avec l'étiquette précédente, par exemple `deploy/deploy.sh 0.9.0 --local`.
- **Données** : le retour arrière ne restaure jamais la base. Si une migration de la version retirée est incompatible avec l'ancienne, il faut restaurer la sauvegarde faite juste avant le déploiement, en suivant le runbook de [operations.md](operations.md#runbook--restaurer-la-production).

Test effectué : déploiements successifs de `v1`, `v2` et `v3`, chacun précédé d'une sauvegarde, puis d'une `v4` dont le backend ne démarre pas. Le script a détecté l'échec en quelques secondes et rétabli `v3`, qui a repassé tous les contrôles.

## Supervision

La pile Prometheus, Alertmanager, Grafana et Jaeger (ARCH-08) reprend la configuration du développement :

```sh
docker compose -f docker-compose.prod.yml --env-file .env.prod --profile observability up -d
```

Ses interfaces n'écoutent que sur `127.0.0.1` du serveur (tunnel SSH pour y accéder). Avant la production, il faut mettre l'adresse e-mail du NOC et le serveur SMTP du CRI dans `observability/alertmanager/alertmanager.yml` ([observability.md](observability.md)).

## Mise à jour et logs

- Mise à jour : `git pull` à la nouvelle version, puis `deploy/deploy.sh <nouvelle étiquette> --build`.
- Logs (JSON pour le backend) : `docker compose -f docker-compose.prod.yml --env-file .env.prod logs -f backend`.
- État : `docker compose -f docker-compose.prod.yml --env-file .env.prod ps`.
