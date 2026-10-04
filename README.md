# TransReal — Monitoring des infrastructures EPT

TransReal est le nouveau projet de plateforme centralisée de supervision des infrastructures réseau et serveurs de l'École Polytechnique de Thiès (EPT).

Le dépôt est actuellement dans sa phase d'initialisation. La stack applicative n'est pas encore arrêtée : aucune technologie, commande de build ou image Docker n'est annoncée comme opérationnelle avant l'ajout du premier composant exécutable.

## Objectifs

- Superviser les routeurs, switches, pare-feu, serveurs et services.
- Collecter des métriques via SNMP, agents, HTTP, TCP et ICMP.
- Détecter les anomalies et gérer le cycle de vie des alertes.
- Fournir tableaux de bord, historique, rapports et journal d'audit.
- Gérer les rôles Administrateur, Opérateur NOC et Lecture seule.
- Envoyer des notifications sans exposer les secrets ni la topologie sensible.

## Cycle fonctionnel cible

```text
Collecte → Détection → Alerte → Notification → Acquittement → Analyse → Résolution
```

## État actuel

| Domaine | État |
| --- | --- |
| Git et conventions | Initialisé |
| Documentation de gouvernance | Initialisée |
| Stack frontend/backend | À décider |
| Tests applicatifs | En attente du code |
| Docker | En attente de la stack |
| Déploiement | En attente de l'infrastructure cible |

Consultez [DEVOPS_AUDIT.md](DEVOPS_AUDIT.md) pour l'état détaillé et les priorités.

## Architecture cible

L'architecture devra séparer au minimum l'interface utilisateur, l'API, les collecteurs, les traitements asynchrones éventuels et le stockage. La forme exacte sera validée par une décision d'architecture avant création du squelette applicatif.

Le schéma cible et les critères de décision figurent dans [docs/devops/architecture.md](docs/devops/architecture.md).

## Prérequis actuels

- Git 2.28 ou supérieur, afin de prendre en charge `git init -b` et les conventions modernes.
- PowerShell 7 pour exécuter le contrôle local du dépôt sur Windows, Linux ou macOS.

Les versions des runtimes applicatifs seront documentées dès que la stack sera choisie.

## Démarrage local

```powershell
git clone https://github.com/boubacarsidibe/Trans-DIC1.git
Set-Location TransReal
Copy-Item .env.example .env
pwsh ./scripts/verify-repository.ps1
```

Le fichier `.env` reste local et ne doit jamais être commité.

## Configuration

Le contrat initial des variables est documenté dans [docs/devops/environment-variables.md](docs/devops/environment-variables.md). Les secrets de CI/CD devront être stockés dans GitHub Actions Secrets ou dans le gestionnaire de secrets de l'infrastructure cible.

## Tests, lint et build

Aucune commande applicative n'existe encore. Chaque composant devra livrer simultanément :

- une commande de lint ;
- une commande de tests reproductible ;
- une commande de build ;
- un healthcheck ;
- les étapes CI correspondantes.

Le contrôle actuellement disponible vérifie la structure du dépôt et empêche le suivi de fichiers sensibles connus :

```powershell
pwsh ./scripts/verify-repository.ps1
```

## Docker et déploiement

Aucun Dockerfile ni fichier Compose n'est créé tant que les runtimes, les ports et les dépendances persistantes ne sont pas définis. Cette retenue évite une infrastructure fictive. Les principes de livraison cible sont décrits dans la documentation DevOps.

## Structure du dépôt

```text
.
├── .github/              # collaboration et automatisations GitHub
├── docs/devops/          # architecture et exploitation
├── scripts/              # contrôles locaux reproductibles
├── .env.example          # contrat de configuration sans secret
├── CONTRIBUTING.md       # règles de contribution
├── DEVOPS_AUDIT.md       # audit et roadmap priorisée
├── SECURITY.md           # politique de sécurité
└── README.md
```

Les dossiers applicatifs seront ajoutés après validation de la stack, sans imposer prématurément des noms tels que `frontend/` ou `backend/`.

## Git et contribution

- `main` représente la version stable.
- `develop` porte l'intégration de la prochaine version.
- Le travail se fait via `feature/*`, `fix/*`, `hotfix/*`, `refactor/*`, `docs/*`, `test/*`, `chore/*` ou `ci/*`.
- Les commits suivent Conventional Commits.
- Les changements rejoignent les branches principales par Pull Request.

Voir [CONTRIBUTING.md](CONTRIBUTING.md) pour la procédure complète.

## CI/CD

Le workflow initial contrôle uniquement la gouvernance et l'absence de fichiers secrets connus. Les jobs applicatifs seront ajoutés avec les composants qu'ils vérifient. Le déploiement restera portable jusqu'à la confirmation de la cible d'hébergement EPT.

## Sécurité

Ne publiez jamais d'adresse interne sensible, communauté SNMP, identifiant équipement, token, clé, mot de passe ou topologie détaillée. Consultez [SECURITY.md](SECURITY.md) avant tout signalement.

## Dépannage

Si le contrôle local échoue, lisez le message associé, retirez le fichier sensible de l'index Git si nécessaire, puis relancez le script. Les procédures applicatives seront ajoutées avec la stack.

## Auteurs et contributeurs

Le projet est porté par l'EPT. La liste des contributeurs sera dérivée de l'historique Git ; aucun compte ou nom d'équipe fictif n'est déclaré.
