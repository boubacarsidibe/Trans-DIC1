# Changelog

Toutes les modifications notables de ce projet seront documentées ici. Le projet suit [Semantic Versioning](https://semver.org/lang/fr/).

## Unreleased

### Added

- Socle initial de gouvernance et d'industrialisation du dépôt.
- Migration Flyway créant cinq équipements fictifs et test automatisé de leur chargement.
- Premier parcours vertical MVP avec métrique CPU synthétique persistée, API protégée et affichage React.
- Connexion JWT avec limitation de débit et verrouillage progressif des comptes (SEC-01).
- API d'inventaire des équipements, avec audit des mutations, et page de gestion côté frontend (US07, US08).
- Modèle persistant complet (28 tables, contraintes `CHECK`, index uniques partiels) et repositories (US02, US03).
- Contrat OpenAPI, Swagger UI hors production et types TypeScript générés (ARCH-02).
- Docker Compose de développement et script de génération du `.env` (ARCH-01).
- Règles d'architecture ArchUnit, couverture JaCoCo et Vitest, Spotless, gitleaks et Trivy dans la CI (ARCH-04, QA-01).
- Renouvellement de jeton par cookie `HttpOnly` avec rotation, détection de réutilisation, déconnexion et nettoyage des sessions expirées (ADR-0005).
- Table des métriques partitionnée par mois, agrégats horaires et purge des partitions expirées (ADR-0004).
- Seuil de retour à la normale (hystérésis) sur les règles d'alerte.
- Moteur d'évaluation des alertes : confirmation du dépassement, hystérésis, déduplication, réouverture avant archivage, trace en maintenance.
- Module de collecte : enregistrement et révocation des agents par jeton, réception des mesures `POST /api/v1/agent/measures`, stockage puis évaluation.
- Test de contrat qui vérifie que `docs/api/openapi.yaml` correspond à l'API réelle.
- API du cycle de vie des alertes (`/api/v1/alertes`) : liste filtrable, détail avec historique, prise en charge, assignation, résolution manuelle, archivage, suppression logique, avec droits par rôle, transitions vérifiées et audit.
- Écran des alertes dans l'interface : liste, filtre par état, actions selon le rôle, historique.
- API d'administration des règles d'alerte (`/api/v1/regles-alertes`) : création, modification, suppression, portée par site ou équipement, validation du seuil de retour, audit.
- Surveillance des agents silencieux : une alerte de collecte critique s'ouvre après 5 minutes sans nouvelle de l'agent et se résout seule à la prochaine mesure.
- Vue d'ensemble (`/api/v1/vue-ensemble`) et tableau de bord alimenté par de vrais chiffres : équipements par statut, alertes actives par sévérité, agents actifs.
- ADR 0002 à 0006 et dossier d'architecture complet (`docs/ARCHITECTURE-COMPLETE.md`, version PDF).

### Changed

- Le backend passe à Java 21.
- Les tests utilisent un vrai PostgreSQL 16 embarqué, sans Docker, à la place de H2 (ADR-0003). Un seul dossier de migrations.
- Le jeton d'accès n'est plus stocké dans `sessionStorage` mais en mémoire.
- `MetricResponse.id` est un entier au lieu d'un UUID.
- Le temps réel prévu passe de WebSocket à SSE (ADR-0006).

### Fixed

- La pagination de la page des équipements ne s'affichait pas avec la vraie API : le frontend lisait `totalPages` à la racine alors que l'API le renvoie dans `page`.
- Dépendance indirecte `undici` mise à jour pour corriger une faille de sévérité haute.

### Security

- Modèle de configuration sans secret et garde-fous contre le suivi de fichiers sensibles.
