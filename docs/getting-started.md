# Guide de démarrage

Ce guide lance Trans-DIC1 sur un poste de développement, sans aide extérieure, en une quinzaine de minutes (hors téléchargements). Il a été suivi tel quel sur un poste Windows 11 avec Windows PowerShell 5.1 et Docker Desktop.

## 1. Prérequis

| Outil | Version | Vérifier |
| --- | --- | --- |
| Git | récent | `git --version` |
| Docker Desktop (ou Docker Engine et Compose v2) | Compose 2.24 ou plus | `docker compose version` |
| PowerShell | Windows PowerShell 5.1 ou PowerShell 7 | `$PSVersionTable.PSVersion` |

Pour développer hors Docker, il faut aussi Java 21, Node.js 24 et PostgreSQL 16 : voir la section 6.

## 2. Récupérer le dépôt et préparer `.env`

```powershell
git clone https://github.com/boubacarsidibe/Trans-DIC1.git
cd Trans-DIC1
powershell -ExecutionPolicy Bypass -File scripts/init-dev-env.ps1
```

Le script crée `.env` à partir de `.env.example`. Il y génère les valeurs vides :
- les mots de passe et secrets : PostgreSQL, JWT, clé de sauvegarde, jeton de métriques, mot de passe Grafana ;
- le compte administrateur initial.

Une valeur déjà renseignée n'est jamais modifiée, et aucun secret n'est affiché. `.env` est ignoré par Git : ne jamais le commiter.

## 3. Démarrer la plateforme

```powershell
docker compose up -d --build
docker compose ps
```

La première construction télécharge les dépendances Maven et npm (plusieurs minutes). La plateforme est prête quand `backend` et `frontend` sont `healthy`.

| Service | Adresse |
| --- | --- |
| Interface | http://localhost:5173 |
| API (santé) | http://localhost:8080/actuator/health |
| Documentation de l'API (Swagger) | http://localhost:8080/swagger-ui.html |
| Boîte mail de test (Mailpit) | http://localhost:8025 |
| PostgreSQL | `127.0.0.1:5433` |

### Un port est déjà pris ?

Les ports publiés se changent dans `.env`, sans toucher aux fichiers versionnés. Par exemple, pour l'API sur 18081 et l'interface sur 15174 :

```text
BACKEND_HOST_PORT=18081
FRONTEND_HOST_PORT=15174
POSTGRES_HOST_PORT=15433
VITE_API_BASE_URL=http://localhost:18081/api/v1
CORS_ALLOWED_ORIGINS=http://localhost:15174
PUBLIC_URL=http://localhost:15174
```

L'adresse de l'API est figée dans l'interface au moment de la construction. Après un changement de `VITE_API_BASE_URL`, relancer `docker compose up -d --build`.

## 4. Se connecter

Ouvrir l'interface et se connecter avec `BOOTSTRAP_ADMIN_USERNAME` (`admin`) et `BOOTSTRAP_ADMIN_PASSWORD`, lu dans `.env`.

La base contient cinq équipements de démonstration fictifs (adresses de documentation RFC 5737). Pour voir des courbes et des alertes sans agent ni SNMP, ajouter `COLLECTION_SIMULATED_ENABLED=true` dans la section `backend` d'un fichier `docker-compose.override.yml` local, puis relancer.

Premiers pas conseillés :
1. créer un compte opérateur NOC et un compte lecteur (Administration > Utilisateurs) ;
2. créer une règle d'alerte (Réglages > Règles) ;
3. suivre le [manuel utilisateur](user-guide/manuel-utilisateur.md).

## 5. Arrêter

```powershell
docker compose down        # arrête en gardant la base
docker compose down -v     # arrête et supprime la base
```

## 6. Développer sans Docker

- Backend : PostgreSQL 16 local ([devops/postgresql.md](devops/postgresql.md)), puis `cd backend` et `.\mvnw.cmd spring-boot:run`.
- Frontend : `cd frontend`, puis `npm ci` et `npm run dev` (http://localhost:5173).
- Une instance de démonstration isolée, dans un schéma dédié avec des mesures simulées : `scripts/run-backend-parallel.ps1`.

Derrière un proxy Maven d'entreprise injoignable, Maven peut échouer avec « Non-resolvable import POM » : passer un `settings.xml` qui pointe vers Maven Central (`-s fichier`, ou la variable `MAVEN_ARGS`).

## 7. Vérifier son environnement

```powershell
cd backend;  .\mvnw.cmd verify                       # tests, couverture, format
cd ..\frontend; npm ci; npm run lint; npm run test:coverage; npm run build
sh e2e/run-e2e.sh                                    # parcours de bout en bout (Chrome requis)
```

## Pour aller plus loin

| Sujet | Document |
| --- | --- |
| Toute la documentation | [docs/README.md](README.md) |
| Architecture, diagrammes C4, décisions | [ARCHITECTURE-COMPLETE.md](ARCHITECTURE-COMPLETE.md), [architecture/diagrams](architecture/diagrams/README.md), [adr](adr/README.md) |
| Déploiement de production | [devops/deployment.md](devops/deployment.md) |
| Variables d'environnement | [devops/environment-variables.md](devops/environment-variables.md) |
| Contribuer | [CONTRIBUTING.md](../CONTRIBUTING.md) |
