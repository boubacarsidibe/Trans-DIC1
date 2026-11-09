# Stratégie CI/CD

## CI actuelle

**Il n'y a plus d'intégration continue.** Les workflows GitHub Actions ont été supprimés (compte GitHub bloqué pour facturation, risque R2) : aucun contrôle ne s'exécute sur les push ni sur les Pull Requests, et rien n'empêche de fusionner un changement qui casse les tests. Les anciens documents qui citent `repository-guard.yml` décrivent un état passé.

Atténuation : `scripts/verify-all.ps1` enchaîne en local les contrôles de l'ancienne CI (dépôt, validation statique de Compose, tests, JaCoCo et Spotless du backend, lint, tests et build du frontend ; en option les parcours de bout en bout sur pile native, les tests de l'agent, gitleaks et trivy). Mode d'emploi, options et limites : [verification-locale.md](verification-locale.md). C'est un contrôle manuel, sans preuve conservée et sans blocage de merge : il réduit le risque, il ne le supprime pas. Il ne demande jamais Docker.

`.github/dependabot.yml` ne couvre que les références GitHub Actions, qui n'existent plus tant que les workflows sont absents. Les gestionnaires de paquets applicatifs n'y figurent pas.

## Ajout d'un composant

Le composant doit exposer des commandes déterministes pour :

1. installer les dépendances depuis un lockfile ;
2. vérifier le format et le lint ;
3. effectuer le type checking si pertinent ;
4. exécuter les tests unitaires et d'intégration ;
5. produire l'artefact ;
6. exécuter un smoke test ou healthcheck.

La CI doit appeler ces mêmes commandes et mettre en cache uniquement les données sûres et reproductibles.

Tant que la CI est absente, `scripts/verify-all.ps1` appelle ces mêmes commandes sur le poste du développeur. Il vise Windows (JDK 21 et Maven Wrapper `.cmd`) : à son retour, une CI Linux appellera directement les commandes du composant (`./mvnw verify`, `npm ci`, `npm run lint`, `npm test`, `npm run build`), pas le script.

## Livraison cible

```text
PR → lint/tests/build/security → develop → staging → validation
   → PR vers main → artefact immuable → approbation → production
   → healthcheck → succès ou rollback
```

Le choix entre images OCI, paquets ou artefacts statiques dépendra de la stack. GHCR reste une option, pas une décision acquise.

## Contrôles de sécurité progressifs

- dépendances et SAST adaptés aux langages détectés ;
- scan de secrets dédié sur l'historique et les changements ;
- scan de l'image si Docker est retenu ;
- blocage initial sur les vulnérabilités critiques et hautes exploitables ;
- triage documenté plutôt qu'un échec permanent ignoré.

## Production

Le déploiement production exige une approbation via un environnement GitHub protégé ou le mécanisme équivalent de la cible. Aucun credential de production ne doit être accessible aux jobs de Pull Request.
