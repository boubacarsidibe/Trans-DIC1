# Explications des classes et des cas d'utilisation — Trans-DIC1

Ce document accompagne les diagrammes de [`DIAGRAMS.md`](DIAGRAMS.md). Il explique le rôle de chaque élément sans confondre le modèle métier cible avec le code actuellement implémenté au Sprint 0.

## 1. Acteurs

### Administrateur

L'Administrateur configure et gouverne la plateforme. Il gère l'inventaire, les comptes, les rôles et les règles d'alerte. Il peut également consulter la supervision et traiter les alertes.

- **Responsabilité principale :** maintenir une configuration fiable et sécurisée.
- **Droits particuliers :** création/modification/suppression d'équipements, changement de rôle, modification des seuils.
- **Règle importante :** toute action sensible doit être auditée.

### Opérateur NOC

L'Opérateur NOC assure la surveillance quotidienne. Il observe les métriques et les alertes, prend en charge les incidents puis les clôture.

- **Responsabilité principale :** détecter et traiter les incidents opérationnels.
- **Droits particuliers :** acquitter et résoudre une alerte.
- **Restriction :** il ne doit pas administrer les rôles ni modifier les paramètres réservés à l'Administrateur.

### Lecteur

Le Lecteur consulte les informations de supervision sans modifier le système.

- **Responsabilité principale :** suivre l'état de l'infrastructure.
- **Droits :** consulter dashboard, métriques, alertes et rapports.
- **Restriction :** aucune mutation métier.

### Planificateur

Le Planificateur est un acteur technique qui déclenche périodiquement les collectes et, éventuellement, la génération planifiée des rapports.

- **Responsabilité principale :** démarrer les traitements au moment configuré.
- **Règle importante :** ne pas accumuler plusieurs collectes concurrentes pour le même cycle.

### Équipement ou Zabbix

Ce système externe fournit les observations brutes utilisées par Trans-DIC1.

- **Responsabilité principale :** exposer les données autorisées de CPU, RAM, disque, réseau ou disponibilité.
- **Contrainte :** seules les cibles approuvées par le CRI doivent être interrogées.

### Service SMTP

Le service SMTP transporte les notifications email produites par Trans-DIC1.

- **Responsabilité principale :** remettre les emails aux destinataires.
- **Contrainte :** les identifiants SMTP ne doivent jamais apparaître dans les journaux ou les notifications.

## 2. Cas d'utilisation

### UC01 — S'authentifier

**Objectif :** permettre à un utilisateur de prouver son identité et d'accéder aux fonctions correspondant à son rôle.

- **Acteurs :** Administrateur, Opérateur NOC, Lecteur.
- **Préconditions :** le compte existe, est actif et possède un rôle valide.
- **Scénario principal :** l'utilisateur fournit ses identifiants ; le système les vérifie ; il délivre un jeton d'accès court et une session renouvelable.
- **Exceptions :** identifiants incorrects, compte désactivé, jeton expiré.
- **Postcondition :** une connexion réussie est auditée sans enregistrer le mot de passe ou le jeton complet.

### UC02 — Gérer l'inventaire

**Objectif :** enregistrer et maintenir la liste des sites, équipements et services supervisés.

- **Acteur principal :** Administrateur.
- **Préconditions :** utilisateur authentifié et autorisé.
- **Scénario principal :** créer, consulter, modifier ou retirer un équipement ; valider ses données ; enregistrer la modification.
- **Règles :** adresse et identité cohérentes, pagination des listes, confirmation avant suppression.
- **Postcondition :** l'inventaire est à jour et la mutation est auditée.

### UC03 — Gérer les utilisateurs et les rôles

**Objectif :** contrôler qui peut accéder au système et avec quels privilèges.

- **Acteur principal :** Administrateur.
- **Scénario principal :** créer ou consulter un compte, modifier son rôle, l'activer ou le désactiver.
- **Règles :** seuls les rôles `ADMIN`, `NOC_OPERATOR` et `VIEWER` sont reconnus ; les changements sont appliqués côté serveur.
- **Postcondition :** chaque changement de rôle est horodaté et audité.

### UC04 — Configurer les règles d'alerte

**Objectif :** définir quand une métrique doit produire une alerte.

- **Acteur principal :** Administrateur.
- **Scénario principal :** choisir le type de métrique, l'opérateur, le seuil, l'unité et la durée de confirmation ; affecter la règle aux équipements concernés.
- **Règles :** valeur et unité compatibles avec la métrique ; modification auditée ; prise en compte sans redémarrage si possible.
- **Exemple :** CPU supérieur à 80 % pendant la durée configurée.

### UC05 — Consulter l'état global

**Objectif :** obtenir une synthèse rapide de la santé des équipements supervisés.

- **Acteurs :** Administrateur, Opérateur NOC, Lecteur.
- **Scénario principal :** le système agrège les dernières métriques et alertes puis affiche un état compréhensible pour chaque équipement.
- **Règles :** les couleurs doivent être accompagnées d'un texte ; les absences de données doivent être visibles.
- **Résultat :** identification rapide des équipements nécessitant une intervention.

### UC06 — Analyser les métriques

**Objectif :** examiner l'évolution des mesures d'un équipement sur une période.

- **Acteurs :** Administrateur, Opérateur NOC, Lecteur.
- **Scénario principal :** choisir un équipement, un type de métrique et une période ; récupérer les données ; afficher graphique, unité et horodatage.
- **Règles :** période valide, résultats triés, pagination ou agrégation pour les gros volumes.
- **Exceptions :** équipement inconnu, période invalide, données absentes.

### UC07 — Consulter les alertes

**Objectif :** visualiser les incidents détectés et leur état de traitement.

- **Acteurs :** Administrateur, Opérateur NOC, Lecteur.
- **Scénario principal :** afficher les alertes et les filtrer par état, sévérité, équipement ou période.
- **Règles :** une sévérité ne doit pas être représentée uniquement par une couleur.
- **Résultat :** l'utilisateur connaît les alertes ouvertes, acquittées et résolues.

### UC08 — Acquitter une alerte

**Objectif :** déclarer qu'un opérateur a pris en charge une alerte ouverte.

- **Acteurs :** Opérateur NOC, Administrateur.
- **Précondition :** l'alerte est `OUVERTE` et l'utilisateur est autorisé.
- **Scénario principal :** l'utilisateur confirme l'acquittement ; l'alerte passe à `ACQUITTEE` ; auteur et date sont enregistrés.
- **Exception :** une alerte déjà résolue refuse la transition.
- **Postcondition :** l'action est persistée et auditée.

### UC09 — Résoudre une alerte

**Objectif :** clôturer un incident après traitement ou retour confirmé à la normale.

- **Acteurs :** Opérateur NOC, Administrateur.
- **Précondition :** l'alerte n'est pas déjà résolue.
- **Scénario principal :** l'utilisateur confirme la résolution ; le système vérifie la transition ; il enregistre auteur et date.
- **Règle :** une alerte `RESOLUE` est terminale dans le modèle actuel.
- **Point à confirmer :** la résolution directe depuis `OUVERTE` est proposée, mais doit être validée avec le métier.

### UC10 — Consulter et exporter les rapports

**Objectif :** obtenir une synthèse de disponibilité, de mesures et d'incidents sur une période.

- **Acteurs :** Administrateur, Opérateur NOC, Lecteur selon les permissions retenues.
- **Scénario principal :** choisir le type et la période ; générer le rapport ; le consulter ou l'exporter en PDF.
- **Règles :** bornes temporelles explicites, date de génération et source indiquées, aucune information sensible exposée.

### UC11 — Collecter les observations

**Objectif :** transformer les observations d'une source en métriques internes exploitables.

- **Acteurs :** Planificateur et Équipement/Zabbix.
- **Scénario principal :** déclencher la collecte ; interroger la source ; normaliser les valeurs ; les horodater en UTC ; les persister ; évaluer les règles d'alerte.
- **Exceptions :** cible injoignable, délai dépassé, valeur invalide.
- **Règles :** reprises bornées, concurrence limitée, aucune fuite de credential.

### UC12 — Notifier un incident

**Objectif :** informer les destinataires lorsqu'une nouvelle alerte est effectivement ouverte.

- **Acteur secondaire :** Service SMTP.
- **Scénario principal :** construire un message assaini ; demander l'envoi ; enregistrer le succès ou l'échec.
- **Exceptions :** indisponibilité SMTP ou erreur de remise ; des reprises bornées sont alors planifiées.
- **Règle :** le contenu ne doit inclure ni secret ni topologie inutile.

## 3. Classes métier

### `Site`

`Site` représente un emplacement logique ou physique de l'EPT regroupant des équipements.

- **Identité :** `SiteId`.
- **Attributs importants :** code et nom.
- **Responsabilité :** organiser l'inventaire par emplacement.
- **Relation :** un site regroupe `0..*` équipements ; un équipement appartient à un site dans le modèle proposé.
- **Invariant recommandé :** le code d'un site est unique.
- **Hypothèse à confirmer :** l'existence éventuelle d'équipements sans site.

### `Equipement`

`Equipement` est la ressource principale faisant l'objet d'une supervision.

- **Identité :** `EquipementId`.
- **Attributs importants :** nom, adresse de gestion, type et état de supervision.
- **Comportements :** activer ou désactiver la supervision.
- **Relations :** appartient à un site, héberge des services, produit des métriques et peut être concerné par plusieurs alertes.
- **Invariants :** adresse valide et unique selon la règle choisie ; aucun secret de collecte exposé.
- **Choix de conception :** serveur, routeur ou switch sont d'abord des valeurs de `TypeEquipement`, pas des sous-classes sans comportement spécialisé prouvé.

### `ServiceSupervise`

`ServiceSupervise` représente une capacité technique observée sur un équipement, par exemple HTTP, DNS ou une base de données.

- **Identité :** `ServiceId`.
- **Attributs importants :** nom, protocole et port.
- **Responsabilité :** préciser ce qui est supervisé au-delà de la machine elle-même.
- **Relation :** appartient à exactement un équipement.
- **Cycle de vie :** dépend fortement de l'équipement ; cette dépendance justifie la composition proposée.

### `Metrique`

`Metrique` représente une mesure normalisée et horodatée.

- **Identité :** `MetriqueId` dans la persistance proposée.
- **Attributs importants :** type, valeur, unité et instant de collecte.
- **Responsabilité :** conserver un fait de mesure exploitable par les graphiques, alertes et rapports.
- **Relation :** appartient à exactement un équipement.
- **Invariants :** horodatage UTC, valeur et unité compatibles avec le type de métrique.
- **Comportement :** elle est essentiellement immuable après sa collecte.

### `RegleAlerte`

`RegleAlerte` décrit la condition permettant de détecter une anomalie.

- **Identité :** `RegleId`.
- **Attributs importants :** type de métrique, opérateur, seuil, unité, durée de confirmation et état actif.
- **Comportements :** évaluer une métrique et modifier le seuil.
- **Relations :** peut surveiller plusieurs équipements et déclencher plusieurs alertes historiques.
- **Invariants :** seuil et unité compatibles avec la métrique ; toute modification est autorisée puis auditée.
- **Exemple :** `CPU > 80 %` pendant une durée définie.

### `Alerte`

`Alerte` représente un incident détecté et suivi jusqu'à sa clôture. Elle constitue une racine d'agrégat.

- **Identité :** `AlerteId`.
- **Attributs importants :** sévérité, état, date d'ouverture, d'acquittement et de résolution.
- **Comportements :** `acquitter()` et `resoudre()`.
- **Relations :** concerne un équipement, résulte d'une règle et provoque éventuellement plusieurs notifications.
- **Invariants :** transitions valides uniquement ; auteur et date obligatoires pour acquittement/résolution ; une seule alerte active par occurrence continue.
- **Cycle de vie :** `OUVERTE → ACQUITTEE → RESOLUE`, avec résolution directe proposée depuis `OUVERTE`.

### `Notification`

`Notification` trace une tentative de remise d'un message associé à une alerte.

- **Identité :** `NotificationId`.
- **Attributs importants :** canal, état de livraison, nombre de tentatives et date d'envoi.
- **Comportements :** marquer envoyée, marquer échouée et éventuellement programmer une reprise.
- **Relation :** appartient à exactement une alerte.
- **Invariants :** reprises bornées ; absence de secrets et de topologie inutile dans le contenu.

### `Rapport`

`Rapport` représente une synthèse métier générée sur une période.

- **Identité :** `RapportId`.
- **Attributs importants :** type, période et date de génération.
- **Comportement :** générer une synthèse ou un export.
- **Relations :** peut synthétiser plusieurs équipements ; un équipement peut apparaître dans plusieurs rapports.
- **Invariants :** début antérieur à la fin ; source, période et date de génération présentes.

### `Utilisateur`

`Utilisateur` représente une identité applicative autorisée à utiliser Trans-DIC1.

- **Identité :** `UtilisateurId`.
- **Attributs importants :** nom d'utilisateur, rôle et état actif.
- **Comportements :** changer de rôle et désactiver le compte.
- **Relations :** peut acquitter ou résoudre plusieurs alertes et être l'auteur de plusieurs entrées d'audit.
- **Invariants :** nom unique, rôle reconnu et mot de passe stocké uniquement sous forme de hash adapté.

### `EntreeAudit`

`EntreeAudit` est la preuve immuable d'une action sensible.

- **Identité :** `AuditId`.
- **Attributs importants :** action, date, identifiant de corrélation, cible et auteur éventuel.
- **Responsabilité :** permettre la traçabilité des connexions, mutations d'inventaire, changements de rôle ou seuil, acquittements et résolutions.
- **Relation :** peut référencer l'utilisateur ayant effectué l'action.
- **Invariant :** une entrée d'audit est append-only et ne contient aucun secret.

## 4. Énumérations et objets-valeurs

### `TypeEquipement`

Classe les équipements sans introduire prématurément une hiérarchie d'héritage : `SERVEUR`, `ROUTEUR`, `SWITCH`, `MACHINE_VIRTUELLE` ou `POINT_ACCES_WIFI`.

### `EtatAlerte`

Décrit la situation courante d'une alerte : `OUVERTE`, `ACQUITTEE` ou `RESOLUE`. Les changements doivent passer par les opérations métier de `Alerte`.

### `RoleUtilisateur`

Définit les permissions générales : `ADMIN`, `NOC_OPERATOR` et `VIEWER`. Le backend reste responsable de leur application réelle.

### `AdresseGestion`

Objet-valeur recommandé encapsulant la validation d'une adresse de gestion. Il évite de disperser les règles de format dans les contrôleurs et services.

### `Periode`

Objet-valeur composé d'une date de début et d'une date de fin. Il garantit notamment que le début précède la fin et sert aux requêtes de métriques et rapports.

## 5. Classes de conception

### `AlertController`

Point d'entrée HTTP des opérations sur les alertes. Il valide la forme de la requête, obtient l'identité authentifiée et délègue au service applicatif. Il ne doit pas contenir les règles de transition métier.

### `CollectionScheduler`

Déclenche la collecte selon la fréquence configurée. Il coordonne le temps, mais ne connaît ni SNMP ni la logique d'alerte.

### `CollectionApplicationService`

Orchestre un cycle de collecte : charger l'équipement, appeler le port de collecte, enregistrer les métriques et demander leur évaluation.

### `AlertApplicationService`

Orchestre les cas d'utilisation relatifs aux alertes. Il charge l'agrégat, appelle ses comportements, persiste le résultat, déclenche la notification et écrit l'audit.

### `NotificationApplicationService`

Prépare et suit les notifications. Il choisit le canal et délègue l'envoi à un port d'infrastructure.

### `AlertRepository`

Contrat de persistance de l'agrégat `Alerte`. Il fournit notamment la recherche d'une alerte active correspondant à une occurrence afin d'éviter les doublons.

### `MetricRepository`

Contrat de stockage et de consultation des métriques, notamment par équipement et période.

### `CollectionPort`

Abstraction du mécanisme de collecte. Le domaine et les services applicatifs l'utilisent sans dépendre de SNMP, Zabbix ou du simulateur.

### `NotificationPort`

Abstraction de l'envoi d'une notification. Une implémentation SMTP peut être remplacée sans modifier le métier.

### Adaptateurs d'infrastructure

- `JpaAlertRepository` et `JpaMetricRepository` traduisent les ports de persistance vers JPA/PostgreSQL.
- `SnmpCollectionAdapter` interroge les équipements réseau en privilégiant SNMPv3.
- `ZabbixCollectionAdapter` interroge Zabbix Agent avec délais et reprises bornées.
- `SimulatedCollectionAdapter` génère des données reproductibles pour le développement.
- `SmtpNotificationAdapter` transmet les emails au service SMTP.

Ces adaptateurs contiennent les détails techniques ; ils ne doivent pas imposer leurs structures aux objets métier.
