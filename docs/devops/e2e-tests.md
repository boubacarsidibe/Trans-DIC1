# Tests de bout en bout (Playwright)

Les tests de bout en bout pilotent un vrai navigateur. Ils passent par l'interface, l'API et une base PostgreSQL réelles, et détectent ainsi les régressions visibles par l'utilisateur, que les tests unitaires (Vitest, JUnit) ne voient pas (QA-02).

## Parcours couverts

| Fichier | Parcours | Rôles |
| --- | --- | --- |
| `frontend/e2e/auth.spec.js` | connexion, tableau de bord (chiffres clés, statuts, sévérités), déconnexion, mauvais mot de passe, page protégée, session conservée au rechargement (cookie de renouvellement) | opérateur NOC, lecteur |
| `frontend/e2e/inventory.spec.js` | ajout d'un équipement par le formulaire ; lecture seule, sans bouton de modification ni accès à l'import | administrateur, opérateur NOC, lecteur |
| `frontend/e2e/alert.spec.js` | alerte ouverte par une vraie mesure d'agent, puis prise en charge, résolution et chronologie ; lecteur sans actions | opérateur NOC, lecteur |
| `frontend/e2e/report.spec.js` | rapport d'incidents sur 30 jours et téléchargement du PDF | administrateur |

## Données et comptes

- La pile de test (`frontend/e2e/compose.e2e.yml`) part d'une base vide, gardée en mémoire. Elle est détruite à la fin de chaque exécution.
- Les secrets (mot de passe PostgreSQL, secret JWT, mot de passe des comptes) sont tirés au hasard à chaque exécution par `run-e2e.sh`. Aucun n'est versionné.
- Comptes de test dédiés :
  - `e2e-admin`, créé par le compte initial (bootstrap) ;
  - `e2e-noc` et `e2e-viewer`, créés par `e2e/global-setup.js`.
- Chaque test crée ses propres données par l'API (site, équipement, règle, agent), avec un suffixe unique et des adresses du préfixe de documentation IPv6 `2001:db8::/32`. Aucun test ne dépend d'un autre, ni de l'ordre d'exécution : ils tournent en parallèle.
- L'alerte n'est pas insérée en base. Elle s'ouvre par le chemin de production : règle CPU > 90 %, agent enregistré, puis mesure à 95 % envoyée avec le jeton de l'agent.

## Lancer les tests

Prérequis : Docker, Node 24, et Google Chrome installé sur le poste (le même navigateur que `scripts/ui-snapshots.mjs`).

```sh
cd frontend
npm ci
sh e2e/run-e2e.sh                      # toute la suite
sh e2e/run-e2e.sh e2e/alert.spec.js    # un seul fichier
```

Le script :
- démarre la pile sur `127.0.0.1` (interface sur le port 15173, API sur le port 18080) ;
- attend que tout soit sain ;
- lance Playwright ;
- supprime la pile.

Sans Chrome, par exemple sur un serveur d'intégration Linux, il faut installer le Chromium de Playwright et vider la variable du navigateur :

```sh
npx playwright install --with-deps chromium
E2E_BROWSER_CHANNEL= sh e2e/run-e2e.sh
```

`E2E_KEEP=1` garde la pile après les tests, pour déboguer. `E2E_WORKERS` règle le nombre de tests en parallèle (2 par défaut).

## Résultats et échecs

- Rapport HTML : `frontend/playwright-report/index.html` (`npx playwright show-report`).
- Pour chaque test en échec, `frontend/test-results/` garde la trace Playwright (actions, réseau, DOM : `npx playwright show-trace <fichier>`), une capture d'écran et une vidéo.
- Ces dossiers sont ignorés par Git. Une CI doit les publier comme artefacts. Le dépôt n'a plus de workflow GitHub Actions depuis leur suppression sur develop : l'intégration se fera avec le retour d'une CI.

Les tests unitaires du frontend (`npm run test:coverage`) excluent le dossier `e2e/`.
