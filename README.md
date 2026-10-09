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
