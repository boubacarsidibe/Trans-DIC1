# Architecture et modélisation UML de Trans-DIC1

Date de l'audit : 5 octobre 2026  
Périmètre : état local du dépôt au Sprint 0  
Statut documentaire : modèle actuel vérifié et modèle cible de conception

## === 1. AUDIT DU REPOSITORY ===

### Synthèse

- **[OBSERVÉ]** Le dépôt contient un socle exécutable : API Spring Boot 4.1.1/Java 17, interface React 19/Vite 8, configuration PostgreSQL 16/Flyway, tests de santé et workflow GitHub Actions.
- **[OBSERVÉ]** Le code métier n'est pas encore implémenté : aucune entité, aucun contrôleur métier, aucun service métier, aucun repository métier et aucune table métier ne sont présents.
- **[OBSERVÉ]** `V1__baseline.sql` est volontairement vide. L'ERD fourni ici décrit donc une **cible**, pas la base actuelle.
- **[OBSERVÉ]** Le frontend ne propose aujourd'hui qu'une page de connexion inactive, une route protégée configurable et un tableau de bord vide.
- **[OBSERVÉ]** L'ADR-0001 et le backlog définissent la cible : inventaire, collecte Zabbix/SNMP, métriques, alertes, notifications, rapports, RBAC et audit.
- **[DÉDUIT]** Le dépôt est à un stade où le modèle conceptuel doit guider l'implémentation, et non être extrait d'un ORM inexistant.
- **[HYPOTHÈSE]** Les détails non décidés (hébergement EPT, schémas exacts d'API, liste des métriques, canaux futurs) sont laissés hors des diagrammes ou explicitement marqués cible.

### Preuves principales

| Sujet | Preuve | Conclusion |
|---|---|---|
| Socle backend | `backend/pom.xml`, `application.properties` | Spring MVC, JPA, Flyway, PostgreSQL et Actuator sont configurés |
| Persistance métier | `V1__baseline.sql` | aucune table métier actuelle |
| UI actuelle | `frontend/src/App.jsx`, `pages/*` | connexion factice et dashboard de Sprint 0 |
| Domaine cible | `docs/architecture/domain-model.md` | Site, équipement, métrique, alerte, notification, utilisateur |
| Règles et rôles | `docs/adr/0001-application-stack.md` | ADMIN, NOC_OPERATOR, VIEWER, JWT cible, audit obligatoire |
| Cas d'usage | `scripts/bootstrap-github-backlog.ps1` | inventaire, collecte, alertes, notifications, rapports, seuils |
| Déploiement | `README.md`, `docs/devops/architecture.md` | local natif actuel ; Docker/Nginx seulement planifiés |
| CI | `.github/workflows/repository-guard.yml` | build/test backend et frontend |

## === 2. COMPRÉHENSION DU DOMAINE ===

Trans-DIC1 est une plateforme interne de supervision des infrastructures réseau et serveurs de l'École Polytechnique de Thiès. Elle doit permettre au CRI/NOC d'inventorier les équipements, de collecter leurs mesures, de détecter les dépassements de seuil, de suivre le cycle de vie des alertes, d'avertir les destinataires et de produire des rapports de disponibilité et d'incidents.

La chaîne métier centrale est :

`Équipement supervisé → collecte → mesure normalisée → évaluation d'une règle → alerte → notification → acquittement/résolution → rapport`.

### Contextes métier proposés

| Contexte | Responsabilité | Statut |
|---|---|---|
| Identity & Access | authentifier, autoriser, gérer les rôles | [OBSERVÉ cible ADR/backlog] |
| Inventory | décrire sites, équipements et services supervisés | [OBSERVÉ cible backlog] |
| Monitoring | collecter et conserver les métriques | [OBSERVÉ cible backlog] |
| Alerting | configurer les seuils, détecter et gérer les alertes | [OBSERVÉ cible backlog] |
| Notification | remettre les avis sans divulguer de secrets | [OBSERVÉ cible backlog] |
| Reporting | agréger disponibilité, métriques et incidents | [OBSERVÉ cible backlog] |
| Audit | tracer les actions sensibles et transitions | [OBSERVÉ cible ADR] |

## === 3. GLOSSAIRE MÉTIER ===

| Terme | Définition métier | Qualification |
|---|---|---|
| Site | emplacement logique ou physique regroupant des équipements | [DÉDUIT] du modèle initial |
| Équipement | ressource réseau ou serveur faisant l'objet d'une supervision | [OBSERVÉ] |
| Service supervisé | capacité applicative ou technique observée sur un équipement | [OBSERVÉ] backlog |
| Métrique | mesure horodatée et normalisée, avec type, valeur et unité | [OBSERVÉ] |
| Règle d'alerte | seuil et durée appliqués à un type de métrique | [DÉDUIT] des US13/US19 |
| Alerte | incident détecté pour un équipement et une règle | [OBSERVÉ] |
| Acquittement | prise en charge explicite d'une alerte par un opérateur | [OBSERVÉ] ADR/modèle initial |
| Résolution | clôture contrôlée d'une alerte avec auteur et date | [OBSERVÉ] |
| Notification | tentative tracée d'informer un destinataire via un canal | [OBSERVÉ] |
| Rapport | synthèse générée sur une période explicite | [OBSERVÉ] US17 |
| Utilisateur | identité applicative portant un rôle d'autorisation | [OBSERVÉ] |
| Collecteur | composant adaptant une source simulée, Zabbix ou SNMP vers des mesures normalisées | [DÉDUIT] ADR/backlog |

## === 4. ACTEURS ET CAS D'UTILISATION ===

### Acteurs

| Acteur | Objectifs | Remarque |
|---|---|---|
| Administrateur | gérer inventaire, utilisateurs, rôles et seuils | rôle externe au système, pas classe métier |
| Opérateur NOC | consulter la supervision, acquitter et résoudre les alertes | rôle opérationnel |
| Lecteur | consulter dashboard, métriques, alertes et rapports | rôle sans mutation métier |
| Planificateur | déclencher périodiquement la collecte et les rapports | acteur système externe/logique |
| Équipement/Zabbix | fournir des observations techniques | système externe |
| Service SMTP | acheminer les notifications email | système externe cible |

Le diagramme [`global-use-cases.mmd`](diagrams/01-use-cases/global-use-cases.mmd) montre les acteurs hors de la frontière Trans-DIC1. Mermaid ne possède pas de syntaxe officielle de cas d'utilisation ; un `flowchart` est utilisé comme notation de substitution. Aucun `include`/`extend` n'est simulé, car les relations systématiques ou conditionnelles ne sont pas assez stabilisées.

## === 5. INVENTAIRE DES ÉTATS ET WORKFLOWS ===

### États confirmés ou déduits

| Objet | États | Source / confiance |
|---|---|---|
| Alerte | OUVERTE, ACQUITTEE, RESOLUE | [DÉDUIT fort] des dates `raisedAt`, `acknowledgedAt`, `resolvedAt` et des verbes ADR |
| Notification | EN_ATTENTE, ENVOYEE, ECHEC | [DÉDUIT] de `DeliveryStatus`, succès/échec et reprises bornées |
| Équipement | état opérationnel vert/orange/rouge | [OBSERVÉ] US11, mais ce sont des états calculés, pas un cycle administratif |
| Rapport | demandé/généré/échoué | [HYPOTHÈSE] insuffisamment spécifiée ; pas de machine à états produite |

### Workflows prioritaires

1. Administrer l'inventaire avec autorisation et audit.
2. Collecter une mesure, la normaliser et la persister.
3. Évaluer un seuil, dédupliquer un incident continu et ouvrir une alerte.
4. Notifier les destinataires avec reprises bornées.
5. Acquitter puis résoudre une alerte en traçant l'auteur et l'heure.
6. Consulter métriques et rapports par équipement et période.

## === 6. SÉLECTION DES DIAGRAMMES UML ===

| Diagramme | Utilité | Question résolue | Priorité | Support Mermaid |
|---|---|---|---|---|
| Cas d'utilisation global | NÉCESSAIRE | Qui poursuit quels objectifs ? | P0 | `flowchart` de substitution |
| Classes métier | NÉCESSAIRE | Quels concepts et invariants structurent le domaine ? | P0 | `classDiagram` |
| Classes de conception | NÉCESSAIRE | Comment isoler interface, application, domaine et infrastructure ? | P0 | `classDiagram` |
| État d'Alerte | NÉCESSAIRE | Quelles transitions sont autorisées ? | P0 | `stateDiagram-v2` |
| Activité incident | TRÈS UTILE | Comment va-t-on d'une mesure à la clôture ? | P1 | `flowchart` |
| Séquence collecte/alerte | NÉCESSAIRE | Quels composants collaborent et dans quel ordre ? | P0 | `sequenceDiagram` |
| Séquence résolution | TRÈS UTILE | Où appliquer autorisation, invariants et audit ? | P1 | `sequenceDiagram` |
| Composants | NÉCESSAIRE | Quels gros blocs logiciels et protocoles ? | P0 | `flowchart` |
| Déploiement actuel/cible | NÉCESSAIRE | Qu'est-ce qui tourne où aujourd'hui et demain ? | P0 | `architecture-beta` |
| ERD cible | NÉCESSAIRE avant US02 | Comment persister le modèle sans le confondre avec lui ? | P0 | `erDiagram` |
| C4 contexte/conteneurs | UTILE | Comment expliquer la plateforme à plusieurs publics ? | P1 | `C4Context`/`C4Container`, expérimental |
| Packages | OPTIONNEL | Comment organiser les futurs packages ? | P2 | non produit : aucun package métier actuel |
| État Notification | UTILE | Comment tracer les reprises d'envoi ? | P2 | non produit séparément : règles encore incomplètes |

## === 7. MODÉLISATION UML ===

### Modèle actuel (AS-IS)

Le modèle applicatif actuel se limite à deux conteneurs de démarrage, un client HTTP, un garde de route configurable, Actuator et une connexion PostgreSQL. Il n'existe pas encore de classes métier à représenter fidèlement.

### Modèle cible recommandé (TO-BE)

Le modèle cible est décrit par les diagrammes de ce dossier. Il est dérivé du backlog, de l'ADR et du modèle initial ; chaque élément non directement implémenté doit donc être validé avant US02.

### Responsabilités des classes centrales

| Classe | Responsabilité / identité | Comportements | Invariants principaux |
|---|---|---|---|
| Site | regrouper des équipements ; `SiteId` | rattacher/retirer un équipement | code de site unique [RECOMMANDATION] |
| Equipement | représenter une cible supervisée ; `EquipementId` | activer/désactiver la supervision | adresse de gestion valide ; aucun secret exposé |
| Metrique | porter une mesure immuable ; `MetriqueId` | aucune mutation après collecte | exactement un équipement, heure UTC, unité cohérente |
| RegleAlerte | définir une condition ; `RegleId` | évaluer une mesure, modifier le seuil | seuil/unité/type compatibles ; modification auditée |
| Alerte | piloter l'incident ; `AlerteId` | acquitter, résoudre | une seule alerte active par équipement/règle/occurrence ; transitions valides |
| Notification | tracer une remise ; `NotificationId` | marquer envoyée/échouée, planifier reprise | aucun secret ; nombre de reprises borné |
| Rapport | synthétiser une période ; `RapportId` | générer | période valide et source/date de génération présentes |
| Utilisateur | porter identité et rôle ; `UtilisateurId` | changer de rôle, désactiver | rôle connu ; changement de rôle audité |
| EntreeAudit | preuve immuable d'une action ; `AuditId` | aucune mutation | auteur/action/date/corrélation présents |

### Justification des associations et cardinalités

- Un `Site` contient zéro à plusieurs `Equipement`; un équipement appartient à exactement un site dans le modèle cible. Cette dernière contrainte reste **[HYPOTHÈSE]** à confirmer si des équipements hors site sont admis.
- Un `Equipement` produit zéro à plusieurs `Metrique`; chaque métrique appartient à exactement un équipement : invariant explicitement documenté.
- Une `RegleAlerte` peut s'appliquer à plusieurs équipements et inversement. L'association `AffectationRegle` rend ce N–N explicite dans l'ERD.
- Une `Alerte` concerne exactement un équipement et une règle ; équipement et règle peuvent avoir plusieurs alertes historiques.
- Une alerte produit zéro à plusieurs notifications ; une notification est rattachée à exactement une alerte.
- Un utilisateur peut acquitter/résoudre plusieurs alertes ; une alerte peut n'avoir aucun acquittant/résolveur tant que la transition n'a pas eu lieu.
- Un rapport couvre zéro à plusieurs équipements et un équipement peut figurer dans plusieurs rapports ; le détail de persistance est volontairement reporté.

### Agrégats DDD proposés

- `Equipement` est racine de l'agrégat d'inventaire. `ServiceSupervise` dépend de son cycle de vie ; les métriques volumineuses restent hors de cet agrégat.
- `Alerte` est racine de l'agrégat incident ; les notifications peuvent être gérées séparément pour permettre reprises et asynchronisme.
- `RegleAlerte` est racine de configuration, avec affectations explicites aux équipements.
- `Utilisateur` est racine d'identité ; `EntreeAudit` est append-only et hors de l'agrégat utilisateur.

### Invariants métier consolidés

1. Une métrique appartient à exactement un équipement et son horodatage est en UTC.
2. Une seule alerte non résolue existe pour un même équipement, une règle et une occurrence continue.
3. Une alerte résolue ne peut plus être acquittée ni rouverte sans un nouveau concept explicitement conçu.
4. Acquittement et résolution enregistrent auteur et date.
5. Une modification de seuil, de rôle ou d'inventaire est auditée.
6. Les secrets SNMP/JWT/SMTP ne sont ni sérialisés vers le client, ni journalisés, ni stockés dans les objets métier présentés.
7. Une notification ne contient pas de topologie inutile et ses reprises sont bornées.

### Revue experte en deux passes

**Pass 1 — proposition.** Le document initial assimilait plusieurs éléments du backlog à des classes et utilisait `Device → Alert` sans représenter la règle à l'origine de l'alerte.

**Pass 2 — critique et correction.**

- `Serveur`, `Routeur`, `Switch`, `MachineVirtuelle` et `PointAccesWifi` ne sont pas modélisés par héritage : le backlog ne prouve aucun comportement polymorphe. `TypeEquipement` suffit à ce stade.
- `Metric` devient `Metrique`, valeur métier immuable et hors de l'agrégat Equipement pour éviter un agrégat non borné.
- `RegleAlerte` est ajoutée car le seuil configurable et audité est un concept métier, pas un champ technique.
- `Alerte` porte son propre comportement de transition plutôt qu'un simple `status` modifié par setter.
- `EntreeAudit` est séparée des entités surveillées : l'audit est transversal et append-only.
- La composition n'est utilisée que pour `Equipement *-- ServiceSupervise`, sous réserve que le service n'existe pas hors de son équipement. Les autres liens sont des associations.
- Le N–N règle/équipement est visible dans l'ERD via une table associative, mais n'encombre pas le modèle conceptuel.

### Problèmes détectés dans le modèle actuel

- Le diagramme initial ne représente ni règle d'alerte, ni audit, ni service supervisé, ni rapport malgré leur présence dans le backlog.
- Les libellés `many` ne sont pas des multiplicités UML précises ; ils sont remplacés par `0..*`.
- `User "0..1" --> "many" Alert` mélange acquittement et résolution dans une relation unique. Le modèle final distingue les deux rôles associationnels.
- `DeviceStatus` mélange potentiellement disponibilité calculée et état administratif ; cette décision doit être clarifiée avant l'enum JPA.
- L'ADR est encore « proposée » et le déploiement cible n'est pas décidé ; les vues TO-BE ne doivent pas être traitées comme réalisées.

### Cohérence globale

Les mêmes états d'alerte (`OUVERTE`, `ACQUITTEE`, `RESOLUE`) sont utilisés dans le modèle de classes, l'activité, les séquences, l'ERD et la machine à états. Les composants de collecte dépendent de ports applicatifs, conformément à l'ADR. Le déploiement actuel reste natif et séparé du déploiement Docker/Nginx cible.

## Matrice de traçabilité

| Acteur | Cas d'utilisation | Workflow | Classes métier | Service cible | Endpoint cible | Tables cibles |
|---|---|---|---|---|---|---|
| Administrateur | gérer inventaire | valider → modifier → auditer | Site, Equipement, EntreeAudit | InventoryApplicationService | `/api/v1/equipements` | `sites`, `equipements`, `audit_entries` |
| Administrateur | configurer seuils | valider → activer → auditer | RegleAlerte, EntreeAudit | AlertRuleApplicationService | `/api/v1/regles-alertes` | `alert_rules`, `equipment_alert_rules`, `audit_entries` |
| Opérateur NOC | acquitter/résoudre | autoriser → transition → auditer | Alerte, Utilisateur, EntreeAudit | AlertApplicationService | `/api/v1/alertes/{id}/acquittement`, `/resolution` | `alerts`, `users`, `audit_entries` |
| Lecteur | consulter supervision | filtrer → lire/agréger | Equipement, Metrique, Alerte | MonitoringQueryService | `/api/v1/metriques`, `/api/v1/alertes` | `equipements`, `metrics`, `alerts` |
| Planificateur | lancer collecte | interroger → normaliser → persister → évaluer | Metrique, RegleAlerte, Alerte | CollectionApplicationService | interne, pas d'endpoint requis | `metrics`, `alert_rules`, `alerts` |
| Service SMTP | remettre notification | composer → envoyer → tracer | Notification | NotificationApplicationService | port sortant SMTP | `notifications` |
| Administrateur/Lecteur | consulter rapports | choisir période → agréger → exporter | Rapport | ReportingApplicationService | `/api/v1/rapports` | `reports` + lectures agrégées |

Les chemins d'API sont **[RECOMMANDATION]** : le dépôt ne fixe que le préfixe `/api/v1`.

## Références normatives et techniques

| Référence | URL | Règle utilisée | Impact |
|---|---|---|---|
| OMG UML 2.5.1 | https://www.omg.org/spec/UML/2.5.1/PDF | sémantique des classes, associations, interactions, activités, états, composants et déploiements | séparation des vues et usage prudent des relations |
| Mermaid Class Diagram | https://mermaid.js.org/syntax/classDiagram.html | syntaxe classes, membres, cardinalités et relations | diagrammes de classes valides |
| Mermaid Sequence Diagram | https://mermaid.js.org/syntax/sequenceDiagram.html | participants, messages, `alt`, `opt` | scénarios non exhaustifs |
| Mermaid State Diagram | https://mermaid.js.org/syntax/stateDiagram.html | états, transitions, pseudo-états initial/final | cycle d'Alerte |
| Mermaid Flowchart | https://mermaid.js.org/syntax/flowchart.html | graphes, sous-graphes | cas d'utilisation et activité de substitution |
| Mermaid ER Diagram | https://mermaid.js.org/syntax/entityRelationshipDiagram.html | entités, attributs, cardinalités | persistance cible distincte du domaine |
| Mermaid Architecture | https://mermaid.js.org/syntax/architecture.html | groupes, services, liens | déploiement actuel/cible |
| Mermaid C4 | https://mermaid.js.org/syntax/c4.html | contexte et conteneurs ; syntaxe expérimentale | vues C4 marquées expérimentales |
| C4 Model | https://c4model.com/diagrams | niveaux de zoom et sélection parcimonieuse | contexte et conteneurs uniquement |
| Spring Boot Actuator | https://docs.spring.io/spring-boot/reference/actuator/monitoring.html | exposition HTTP de `/actuator/health` | composant et déploiement AS-IS |

## Validation et limites

- Tous les fichiers utilisent des syntaxes documentées par Mermaid ; `usecase-beta` a été écarté car non officiel.
- Aucun paquet Mermaid n'est présent dans le dépôt : la validation est une revue syntaxique statique, pas un rendu automatisé local.
- `architecture-beta` requiert Mermaid 11.1 ou supérieur et C4 est officiellement expérimental. Si le moteur de rendu cible est plus ancien, les vues C4 et déploiement devront être converties en `flowchart`.
- Le modèle doit être revalidé avec le CRI avant implémentation de US02, en particulier la hiérarchie des sites, les types d'équipement, les unités, la règle de déduplication et les transitions de retour à la normale.

