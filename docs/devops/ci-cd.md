# Stratégie CI/CD

## CI actuelle

`repository-guard.yml` exécute le contrôle d'hygiène sur les push et Pull Requests visant `main` ou `develop`. Il ne simule aucun test applicatif.

## Ajout d'un composant

Le composant doit exposer des commandes déterministes pour :

1. installer les dépendances depuis un lockfile ;
2. vérifier le format et le lint ;
3. effectuer le type checking si pertinent ;
4. exécuter les tests unitaires et d'intégration ;
5. produire l'artefact ;
6. exécuter un smoke test ou healthcheck.

La CI doit appeler ces mêmes commandes et mettre en cache uniquement les données sûres et reproductibles.

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

