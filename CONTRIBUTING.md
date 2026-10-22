# Contribuer à Trans-DIC1

Merci de contribuer à la plateforme de monitoring EPT. Les changements doivent rester reproductibles, testables et exempts d'informations sensibles.

## Installation du dépôt

1. Clonez le dépôt puis placez-vous à sa racine.
2. Copiez `.env.example` vers `.env` sans modifier le modèle versionné avec des secrets.
3. Installez les runtimes documentés par le composant concerné.
4. Exécutez `pwsh ./scripts/verify-repository.ps1`.

## Branches

- `main` : versions stables et production.
- `develop` : intégration de la prochaine version.
- Branches temporaires : `feature/*`, `fix/*`, `hotfix/*`, `refactor/*`, `docs/*`, `test/*`, `chore/*`, `ci/*`.

Créez les branches de travail depuis `develop`, sauf les correctifs urgents de production qui partent de `main`.

## Commits

Utilisez Conventional Commits :

```text
feat(alerts): add incident acknowledgement
fix(snmp): handle unreachable devices
docs(readme): document local setup
ci(github): validate repository hygiene
```

Types acceptés : `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore` et `revert`.

## Qualité et tests

Avant chaque push :

1. exécutez le lint du composant ;
2. exécutez ses tests ;
3. vérifiez son build ;
4. exécutez `pwsh ./scripts/verify-repository.ps1` ;
5. inspectez `git status` et `git diff`.

### Contrôles automatiques de la CI

Chaque push et chaque Pull Request exécutent ces contrôles. Reproduisez-les en local avant de pousser.

| Contrôle | Échoue quand | Commande locale | Pour corriger |
|---|---|---|---|
| Tests backend | un test échoue | `cd backend && ./mvnw verify` | corriger le code ou le test |
| Couverture backend (JaCoCo) | lignes < 80 % ou branches < 60 % | `./mvnw verify`, rapport dans `backend/target/site/jacoco/index.html` | ajouter des tests |
| Format Java (Spotless) | un fichier **modifié par rapport à `origin/develop`** n'est pas au format Google Java Format | `./mvnw spotless:check` | `./mvnw spotless:apply` |
| Lint frontend | `oxlint` signale une erreur | `cd frontend && npm run lint` | corriger l'erreur signalée |
| Tests et couverture frontend (Vitest) | un test échoue ou la couverture passe sous 75 % (65 % pour les branches) | `npm run test:coverage` | ajouter des tests |
| Audit npm | une dépendance a une faille HIGH ou CRITICAL | `npm audit --audit-level=high` | `npm audit fix`, ou mettre à jour la dépendance |
| Secrets (Gitleaks) | un secret apparaît dans le code ou l'historique | — | retirer le secret, **le révoquer et en générer un nouveau** : le supprimer d'un commit ne suffit pas |
| Vulnérabilités (Trivy) | une dépendance Java ou npm a une faille HIGH ou CRITICAL corrigeable | — | mettre à jour la dépendance vers la version corrigée |

Spotless ne contrôle que les fichiers que vous modifiez : le code existant n'est pas reformaté d'un coup. Les seuils de couverture sont dans `backend/pom.xml` (JaCoCo) et `frontend/vite.config.js` ; ils ne doivent jamais être baissés pour faire passer une Pull Request. Seules les entités passives du modèle persistant (champs et accesseurs sans logique) sont exclues de la mesure JaCoCo, la liste est dans `backend/pom.xml` et leur mapping est testé par `PersistenceModelTests`. Les rapports de couverture sont téléchargeables dans les artefacts du run GitHub Actions.

Une nouvelle fonctionnalité doit inclure des tests proportionnés au risque. Une correction doit, si possible, contenir un test qui reproduit la régression.

## Pull Requests

- Ciblez `develop` pour le travail courant et `main` pour une release ou un hotfix.
- Gardez une PR centrée sur un seul objectif.
- Remplissez le modèle, documentez la méthode de test et signalez toute migration.
- Mettez à jour la documentation et `.env.example` lorsqu'une variable est ajoutée.
- Attendez le succès des contrôles requis et la résolution des conversations.

## Revue de code

La revue vérifie le comportement, les tests, la sécurité, les migrations, l'observabilité et les effets opérationnels. L'auteur ne doit pas approuver seul un changement sensible lorsque plusieurs mainteneurs sont disponibles.

## Documentation

Toute commande publiée doit avoir été exécutée. N'annoncez pas de workflow, de badge, de service ou d'environnement inexistant.

## Sécurité

N'ajoutez jamais de secret, donnée réseau interne, clé privée ou credential aux commits, logs, captures ou fixtures. Pour une vulnérabilité, suivez [SECURITY.md](SECURITY.md) et n'ouvrez pas d'Issue publique contenant des détails exploitables.
