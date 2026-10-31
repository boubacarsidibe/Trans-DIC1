# Changelog

Toutes les modifications notables de ce projet seront documentées ici. Le projet suit [Semantic Versioning](https://semver.org/lang/fr/).

## Unreleased

### Added

- Connexion unique OIDC avec le fournisseur d'identité de l'EPT : Authorization Code + PKCE côté écran, jetons du fournisseur validés côté serveur, rôles convertis, connexion locale gardée en secours, Keycloak de développement (ARCH-09, ADR 0017).
- Relais des événements entre instances du backend par PostgreSQL LISTEN/NOTIFY avec table d'envoi et rattrapage (ARCH-10, ADR 0016).
- Chaîne de livraison par scripts (construction, analyse des images, déploiement en staging, promotion) et chart Helm pour Kubernetes (OPS-01, OPS-03).
- Détection d'anomalies des métriques par profil jour et heure, alertes de type Anomalie (FEAT-12, ADR 0015).
- Regroupement des alertes de collecte sous une alerte racine grâce à la topologie (FEAT-11, ADR 0014).
- Topologie du réseau et calcul de l'impact d'une panne (FEAT-10).
- Vues de tableau de bord personnelles et partagées, écran mural NOC (FEAT-08).
- Disponibilité et SLA par site, objectif par site, maintenances exclues, export PDF (FEAT-09, ADR 0013).
- Plusieurs opérateurs assignés à une même alerte (FEAT-03).
- Plan de validation sur l'infrastructure de l'EPT, proposition de seuils pour le CRI, brouillon du rapport final, trame de soutenance et scénario de démonstration (`docs/validation/`, `docs/soutenance/`).

- Journal d'audit consultable et exportable en CSV par les administrateurs, en lecture seule (FEAT-06).
- Escalade des alertes non prises en charge vers les administrateurs (FEAT-03).
- Notifications vers e-mail, webhook signé, Teams et Slack, modèles de messages modifiables, historique et envoi de test ; file d'envoi idempotente et événements internes (ARCH-07, FEAT-05).
- Verrou en base des tâches planifiées de collecte (une seule instance à la fois), pool de threads dédié, état et durée de chaque exécution en base et en métriques (ARCH-06).
- Hooks Git versionnés (Conventional Commits, lint avant commit), CODEOWNERS par dossier et release-please pour les versions (ENG-01).
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
- Écrans d'administration : règles d'alerte, maintenances et utilisateurs (réservé aux administrateurs), avec la même validation que l'API.
- Flux temps réel SSE (`GET /api/v1/flux`) : le tableau de bord et les alertes se rafraîchissent à chaque changement, avec reconnexion automatique (ADR-0006).
- Lecture de l'état de l'agent d'un équipement (`GET /api/v1/equipements/{id}/agent`).
- Écrans des sites et de la configuration de la collecte (agent et SNMP) par équipement, réservés aux administrateurs.
- Script `scripts/run-backend-parallel.ps1` : lance une instance de démonstration isolée sur le port 8090.
- Port de collecte `MetricSource` et source simulée (CPU, mémoire, disque, disponibilité) reproductible, activée par `collection.simulated.enabled` ; tours sans chevauchement, tentatives bornées, compteurs Micrometer (US09).
- API de l'historique des mesures `GET /api/v1/equipements/{id}/metriques` (agrégats moyenne/min/max, pas automatique, 400 jours et 5 000 points au plus) et liste des indicateurs d'un équipement (US10).
- Écran d'historique avec graphiques CPU, mémoire et disque, choix de période, tableau de valeurs accessible et trous de mesure visibles (US12).
- Export PDF des rapports de disponibilité et d'incidents (`format=pdf`, OpenPDF) avec période et date de génération, et bouton « Exporter en PDF » (US18).
- Écran des alertes dans l'interface : liste, filtre par état, actions selon le rôle, historique.
- API d'administration des règles d'alerte (`/api/v1/regles-alertes`) : création, modification, suppression, portée par site ou équipement, validation du seuil de retour, audit.
- API de gestion des sites (`/api/v1/sites`) : création, modification avec code immuable, suppression d'un site vide, audit. Le formulaire d'équipement liste les vrais sites au lieu de les déduire des équipements, ce qui permettait de choisir seulement des sites déjà occupés.
- API de gestion des comptes (`/api/v1/utilisateurs`) : création, changement de rôle, activation, réinitialisation du mot de passe, réservée aux administrateurs ; on ne modifie pas son propre compte, le dernier administrateur est protégé, les connexions du compte concerné sont révoquées, et rien de sensible n'entre dans l'audit.
- Notifications par e-mail : à l'ouverture et à la résolution d'une alerte, un e-mail part aux utilisateurs qui l'ont demandé (préférence inactive par défaut, sévérité minimale). Envoi différé avec reprises bornées, aucun envoi pendant une maintenance, aucune adresse IP dans les messages (ADR-0007). Écran « Mes notifications ».
- API des maintenances (`/api/v1/maintenances`) : planification sur un site ou un équipement, annulation, état calculé (à venir, en cours, terminée, annulée) ; les alertes de la cible sont tracées sans notification pendant la fenêtre.
- Rapports de disponibilité et d'incidents sur une période (`/api/v1/rapports`) : coupures reconstituées depuis l'historique des alertes, délai moyen de résolution, équipements les plus touchés ; export CSV prêt pour un tableur (formules neutralisées) et écran dédié avec impression.
- Statut des équipements mis à jour par la collecte : hors service quand l'agent ou l'équipement ne répond plus, en service dès qu'une mesure arrive ; un équipement en maintenance garde son statut.
- Collecte SNMP v2c et v3 (SNMP4J) : lecture planifiée des OID d'un équipement, mesures stockées et évaluées, alerte de collecte en cas de silence et résolution à la reprise. Configuration par équipement via `/api/v1/equipements/{id}/collecte-snmp` ; communauté et mots de passe uniquement par référence `env:NOM`, jamais en base ni dans l'API.
- Surveillance des agents silencieux : une alerte de collecte critique s'ouvre après 5 minutes sans nouvelle de l'agent et se résout seule à la prochaine mesure.
- Vue d'ensemble (`/api/v1/vue-ensemble`) et tableau de bord alimenté par de vrais chiffres : équipements par statut, alertes actives par sévérité, agents actifs.
- ADR 0002 à 0006 et dossier d'architecture complet (`docs/ARCHITECTURE-COMPLETE.md`, version PDF).

### Changed

- Le journal d'audit des règles d'alerte garde l'ancienne et la nouvelle valeur (seuil, retour, confirmation, sévérité), pour revenir à un seuil précédent.
- Nouvel habillage de l'interface : connexion avec photo, feuille de travail gris clair sur fond brun, tableaux et récapitulatifs uniformes.
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
