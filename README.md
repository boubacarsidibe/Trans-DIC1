# Trans-DIC1 — Monitoring des infrastructures EPT

Trans-DIC1 est la plateforme centralisée de supervision des infrastructures réseau et serveurs de l'École Polytechnique de Thiès (EPT).

## État du projet

Le socle du Sprint 0 est opérationnel : API Spring Boot, interface React, base PostgreSQL locale, migrations Flyway, cinq équipements fictifs, tests automatisés et workflow CI. Les fonctions métier d'inventaire, de collecte et d'alerting seront livrées dans les user stories suivantes.

| Composant | Stack |
| --- | --- |
| Backend | Java 17, Spring Boot 4.1.1, Maven Wrapper |
| Frontend | Node.js 24, React 19, Vite 8, Tailwind CSS 4 |
| Données | PostgreSQL 16 natif, Flyway |
| Tests | JUnit/H2 et Vitest/Testing Library |

La décision complète est dans [ADR-0001](docs/adr/0001-application-stack.md), le modèle initial dans [docs/architecture/domain-model.md](docs/architecture/domain-model.md) et le backlog dans [docs/product-backlog.md](docs/product-backlog.md).

## Prérequis

- Git 2.28+
- Java 17 ; Maven n'est pas requis car le wrapper est versionné
- Node.js 24 et npm 11
- PostgreSQL 16 installé localement
- PowerShell 7 pour les scripts de contrôle sur Windows, Linux ou macOS

## Démarrage local

```powershell
git clone https://github.com/boubacarsidibe/Trans-DIC1.git
Set-Location Trans-DIC1
Copy-Item .env.example .env
# Renseigner DATABASE_PASSWORD dans .env, puis vérifier PostgreSQL local
pwsh ./scripts/check-local-postgresql.ps1

Set-Location backend
.\mvnw.cmd spring-boot:run
```

Dans un second terminal :

```powershell
Set-Location frontend
npm ci
npm run dev
```

- Frontend : `http://localhost:5173`
- API/healthcheck : `http://localhost:8080/actuator/health`

La procédure PostgreSQL native complète est documentée dans [docs/devops/postgresql.md](docs/devops/postgresql.md). Le fichier `.env` reste local et ne doit jamais être commité. Docker n'est pas requis.

### Démarrage avec Docker Compose

Une alternative sans installation de PostgreSQL, Java ni Node :

```powershell
Copy-Item .env.example .env
# Dans .env, renseigner DATABASE_PASSWORD, JWT_SECRET (32 caractères minimum,
# par exemple : openssl rand -hex 32) et, pour créer un premier compte,
# BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_EMAIL et BOOTSTRAP_ADMIN_PASSWORD.
docker compose up --build
```

| Service | URL | Remarque |
|---|---|---|
| Frontend | `http://localhost:5173` | nginx sans privilèges |
| API | `http://localhost:8080/actuator/health` | image JRE 17, utilisateur non-root |
| Mailpit | `http://localhost:8025` | boîte mail de test, SMTP sur le port 1025 |
| PostgreSQL | `127.0.0.1:5433` | volume `postgres-data` ; 5433 évite le conflit avec un PostgreSQL local |

Si `DATABASE_PASSWORD` ou `JWT_SECRET` manque, `docker compose` s'arrête avec un message explicite. `docker compose down -v` supprime aussi la base.

## Tests, lint et build

```powershell
# Contrôle du dépôt
pwsh ./scripts/verify-repository.ps1

# Backend
Set-Location backend
.\mvnw.cmd verify

# Frontend
Set-Location ..\frontend
npm ci
npm run lint
npm test
npm run build
```

Les tests backend utilisent une base H2 en mémoire, isolée de PostgreSQL local, et appliquent la migration Flyway. Le workflow GitHub Actions exécute les mêmes contrôles sur chaque push et Pull Request dès que GitHub Actions est disponible pour le dépôt.

## Structure

```text
.
├── .github/              # collaboration et CI GitHub
├── backend/              # API Spring Boot
├── frontend/             # application React
├── docs/adr/             # décisions d'architecture
├── docs/architecture/    # modèle et flux fonctionnels
├── docs/devops/          # exploitation locale et cible
├── scripts/              # contrôles reproductibles
└── .env.example          # contrat de configuration sans secret
```

## Contribution et sécurité

- `main` représente la version stable et `develop` l'intégration.
- Le travail passe par des branches `feature/*`, `fix/*`, `docs/*`, `test/*`, `chore/*` ou `ci/*` et des Pull Requests.
- Les commits suivent Conventional Commits.
- Aucun secret, communauté SNMP, token, adresse interne ou topologie sensible ne doit être versionné ou journalisé.

Consultez [CONTRIBUTING.md](CONTRIBUTING.md) et [SECURITY.md](SECURITY.md) avant toute contribution.
