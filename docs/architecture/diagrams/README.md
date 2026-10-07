# Catalogue des diagrammes

Ce catalogue complète `../00-overview.md`. Les éléments qualifiés « cible » proviennent de l'ADR et du backlog ; ils ne sont pas encore implémentés au Sprint 0.

## Métadonnées par diagramme

### `01-use-cases/global-use-cases.mmd`

> Proposition initiale remplacée, après entretien métier, par `validated-global-use-cases.mmd`, `monitoring-use-cases.mmd` et `administration-use-cases.mmd`. La synthèse validée se trouve dans `../../USE-CASES-VALIDES.md`.

- **Nom :** cas d'utilisation global.
- **Objectif :** relier rôles externes et objectifs métier dans la frontière Trans-DIC1.
- **Question :** qui utilise le système, pour obtenir quel résultat ?
- **Périmètre :** parcours décrits par l'ADR et les US05 à US22.
- **Exclus :** clics UI, endpoints et détails techniques.
- **Sources dépôt :** ADR-0001, backlog, `App.jsx`.
- **Règles appliquées :** acteurs externes, cas nommés par objectifs ; aucun `include`/`extend` non prouvé.
- **Hypothèse :** le planificateur est traité comme acteur logique.
- **Support :** Mermaid n'offre pas de use-case natif ; `flowchart` est une substitution visuelle documentée.

### `02-domain/domain-overview.mmd`

- **Nom :** modèle conceptuel métier cible.
- **Objectif :** présenter entités, valeurs, comportements et relations essentiels sans ORM ni framework.
- **Question :** quels objets fondamentaux collaborent pour superviser l'infrastructure ?
- **Périmètre :** inventaire, monitoring, alerting, notification, reporting, identité et audit.
- **Exclus :** DTO, contrôleurs, JPA, HTTP, timestamps techniques génériques.
- **Sources dépôt :** modèle initial, ADR-0001, US02, US13 à US19.
- **Règles appliquées :** multiplicités aux deux extrémités ; composition limitée au service dépendant de l'équipement ; opérations métier plutôt que setters.
- **Hypothèses :** un équipement appartient à un site ; un service supervisé n'existe pas sans équipement.

### `02-domain/design-model.mmd`

- **Nom :** modèle de conception cible.
- **Objectif :** séparer boundary, application, domaine, ports et adaptateurs.
- **Question :** comment implémenter les scénarios sans coupler le domaine à Spring, JPA, SNMP ou Zabbix ?
- **Périmètre :** collecte et alertes, les flux les plus risqués.
- **Exclus :** détails React, classes Spring générées et méthodes CRUD exhaustives.
- **Sources dépôt :** ADR-0001 (ports applicatifs), US03, US07, US09, US13, US14, US16, US21, US22.
- **Règles appliquées :** réalisation pour les implémentations de ports, dépendances pour les usages, aucune classe d'infrastructure dans le modèle métier.
- **Hypothèse :** le collecteur pourra être un processus séparé ; ce choix reste ouvert.

### `03-sequences/collect-measure-and-open-alert.mmd`

- **Nom :** séquence collecte vers alerte.
- **Objectif :** montrer l'ordre des interactions du scénario central.
- **Question :** comment une observation devient-elle métrique puis alerte notifiée ?
- **Périmètre :** chemin nominal, déduplication et absence de dépassement.
- **Exclus :** sérialisation, requêtes SQL et détails des protocoles.
- **Sources dépôt :** modèle initial, US09, US13, US16, US21, US22.
- **Règles appliquées :** temps vertical ; `loop` pour les métriques ; `alt` pour les résultats exclusifs.
- **Hypothèse :** une clé d'occurrence permet la déduplication.

### `03-sequences/acknowledge-and-resolve-alert.mmd`

- **Nom :** séquence de traitement d'une alerte.
- **Objectif :** localiser autorisation, invariant de transition, persistance et audit.
- **Question :** comment l'opérateur acquitte-t-il puis résout-il une alerte ?
- **Périmètre :** rôle NOC et API cible.
- **Exclus :** renouvellement JWT et implémentation UI.
- **Sources dépôt :** ADR-0001, US05, US14, US15.
- **Règles appliquées :** messages orientés intention ; alternatives autorisé/interdit et valide/invalide.
- **Hypothèse :** résolution directe d'une alerte ouverte autorisée, à valider métier.

### `04-activities/incident-lifecycle.mmd`

- **Nom :** activité de gestion d'un incident.
- **Objectif :** rendre visibles décisions et reprises du processus complet.
- **Question :** quelles décisions mènent d'une collecte à une résolution ?
- **Périmètre :** collecte, validation, seuil, déduplication, notification et clôture.
- **Exclus :** composants responsables, couverts par les séquences.
- **Sources dépôt :** US09, US13, US14, US16, règles d'exploitation.
- **Règles appliquées :** actions et décisions distinctes, début et fin explicites.
- **Hypothèse :** le retour à la normale est un prérequis de résolution opérationnelle.

### `05-states/alert-state.mmd`

- **Nom :** machine à états de l'alerte.
- **Objectif :** interdire les transitions incohérentes.
- **Question :** dans quels états une alerte peut-elle se trouver et quels événements la font évoluer ?
- **Périmètre :** cycle métier documenté.
- **Exclus :** échec de notification et disponibilité d'équipement.
- **Sources dépôt :** modèle initial, ADR-0001, US14.
- **Règles appliquées :** états nommés par situations, transitions par événements, état terminal explicite.
- **Hypothèse :** résolution directe depuis OUVERTE ; pas de réouverture.

### `06-components/components.mmd`

- **Nom :** composants logiciels cibles.
- **Objectif :** montrer les blocs et protocoles majeurs.
- **Question :** quelles responsabilités macroscopiques composent Trans-DIC1 ?
- **Périmètre :** frontend, backend logique, persistance et systèmes externes.
- **Exclus :** classes et détails de déploiement.
- **Sources dépôt :** README, ADR-0001, architecture DevOps, backlog.
- **Règles appliquées :** composants à granularité cohérente, interfaces annotées par protocole.
- **Hypothèse :** séparation logique des composants dans un backend initialement monolithique.

### `07-deployment/deployment-current.mmd`

- **Nom :** déploiement local actuel.
- **Objectif :** représenter seulement l'environnement exécutable observé.
- **Question :** où tournent les artefacts au Sprint 0 ?
- **Périmètre :** navigateur, Vite, Spring Boot et PostgreSQL local.
- **Exclus :** Docker, Nginx, cloud et production.
- **Sources dépôt :** README, configurations Vite/Spring/PostgreSQL.
- **Règles appliquées :** nœuds d'exécution et connexions observables.
- **Hypothèse :** tous les services de développement tournent sur le même poste.

### `07-deployment/deployment-target.mmd`

- **Nom :** déploiement cible conditionnel.
- **Objectif :** matérialiser US23 sans inventer un fournisseur cloud.
- **Question :** quelle topologie minimale Docker/Nginx est envisagée ?
- **Périmètre :** hébergement EPT abstrait, proxy, frontend, API, collecteur, DB et SMTP.
- **Exclus :** Kubernetes, AWS/Azure, DNS/TLS exacts, réplication et queue.
- **Sources dépôt :** US23 et architecture DevOps.
- **Règles appliquées :** environnements d'exécution distincts et connexions annotées.
- **Hypothèse :** collecteur déployable séparément ; cible d'hébergement non décidée.

### `08-data/erd-target.mmd`

- **Nom :** ERD relationnel cible.
- **Objectif :** préparer les migrations sans confondre tables et objets métier.
- **Question :** quelles clés et cardinalités minimales supporteront les cas d'usage ?
- **Périmètre :** persistance opérationnelle centrale.
- **Exclus :** partitions de métriques, détails rapports, refresh tokens et schéma d'audit complet.
- **Sources dépôt :** modèle initial, ADR-0001, US02/US03/US13/US19.
- **Règles appliquées :** PK/FK explicites, table associative pour le N–N, relations en notation crow's foot.
- **Hypothèses :** clé d'occurrence unique ; site obligatoire ; noms physiques à valider avant migration.

### `09-c4/context.mmd` et `09-c4/containers.mmd`

- **Nom :** C4 contexte et conteneurs cibles.
- **Objectif :** fournir deux niveaux de zoom pour les parties prenantes et l'équipe.
- **Question :** avec qui le système interagit-il, puis quels conteneurs le réalisent ?
- **Périmètre :** système global et cible applicative.
- **Exclus :** niveau code et component C4, redondants à ce stade.
- **Sources dépôt :** README, ADR-0001, architecture DevOps et backlog.
- **Règles appliquées :** une abstraction par niveau ; personnes/systèmes externes au contexte ; technologies dans les conteneurs.
- **Hypothèses :** worker séparé et WebSocket cible.
- **Support :** syntaxe Mermaid C4 officielle mais expérimentale.

## Références par catégorie

- UML : https://www.omg.org/spec/UML/2.5.1/PDF — sémantique normative commune.
- Classes : https://mermaid.js.org/syntax/classDiagram.html — membres, relations et cardinalités.
- Séquences : https://mermaid.js.org/syntax/sequenceDiagram.html — acteurs, participants, boucles et alternatives.
- États : https://mermaid.js.org/syntax/stateDiagram.html — pseudo-états et transitions.
- Activités et cas d'utilisation de substitution : https://mermaid.js.org/syntax/flowchart.html — nœuds, décisions et sous-graphes.
- ERD : https://mermaid.js.org/syntax/entityRelationshipDiagram.html — entités, attributs et crow's foot.
- Architecture : https://mermaid.js.org/syntax/architecture.html — syntaxe disponible depuis Mermaid 11.1.
- C4 : https://mermaid.js.org/syntax/c4.html et https://c4model.com/diagrams — syntaxe expérimentale et niveaux de zoom.
