# Contribuer à TransReal

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

