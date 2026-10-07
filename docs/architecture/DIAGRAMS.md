# Galerie des diagrammes — Trans-DIC1

> Les vues métier et d'architecture ci-dessous représentent la cible recommandée, sauf le déploiement local explicitement indiqué comme actuel.

Les explications détaillées de chaque acteur, cas d'utilisation et classe sont disponibles dans [`EXPLICATIONS.md`](EXPLICATIONS.md).

> **Mise à jour après validation métier :** le premier diagramme ci-dessous est conservé comme proposition initiale. Les cas d'utilisation validés le 6 octobre 2026 sont disponibles dans [`USE-CASES-VALIDES.md`](USE-CASES-VALIDES.md) et dans les trois fichiers `validated-global-use-cases.mmd`, `monitoring-use-cases.mmd` et `administration-use-cases.mmd`.

## 1. Cas d'utilisation global

```mermaid
flowchart LR
    admin["Administrateur"]
    noc["Opérateur NOC"]
    viewer["Lecteur"]
    scheduler["Planificateur"]
    source["Équipement / Zabbix"]
    smtp["Service SMTP"]
    subgraph system["Trans-DIC1"]
        authenticate(["S'authentifier"])
        inventory(["Gérer l'inventaire"])
        users(["Gérer utilisateurs et rôles"])
        thresholds(["Configurer les règles d'alerte"])
        dashboard(["Consulter l'état global"])
        metrics(["Analyser les métriques"])
        alerts(["Consulter les alertes"])
        acknowledge(["Acquitter une alerte"])
        resolve(["Résoudre une alerte"])
        reports(["Consulter et exporter les rapports"])
        collect(["Collecter les observations"])
        notify(["Notifier un incident"])
    end
    admin --> authenticate & inventory & users & thresholds & dashboard & metrics & alerts & acknowledge & resolve & reports
    noc --> authenticate & dashboard & metrics & alerts & acknowledge & resolve & reports
    viewer --> authenticate & dashboard & metrics & alerts & reports
    scheduler --> collect
    source --> collect
    notify --> smtp
```

## 2. Modèle conceptuel métier

```mermaid
classDiagram
    direction LR
    class Site {
        +SiteId id
        +String code
        +String nom
    }
    class Equipement {
        +EquipementId id
        +String nom
        +AdresseGestion adresse
        +TypeEquipement type
        +EtatSupervision etat
        +activerSupervision()
        +desactiverSupervision()
    }
    class ServiceSupervise {
        +ServiceId id
        +String nom
        +String protocole
        +int port
    }
    class Metrique {
        +MetriqueId id
        +TypeMetrique type
        +Decimal valeur
        +String unite
        +Instant collecteeA
    }
    class RegleAlerte {
        +RegleId id
        +TypeMetrique typeMetrique
        +Decimal seuil
        +Duration dureeConfirmation
        +evaluer(Metrique)
        +modifierSeuil(Decimal)
    }
    class Alerte {
        +AlerteId id
        +Severite severite
        +EtatAlerte etat
        +Instant ouverteA
        +acquitter(Utilisateur)
        +resoudre(Utilisateur)
    }
    class Notification {
        +NotificationId id
        +CanalNotification canal
        +EtatLivraison etat
        +int tentatives
        +marquerEnvoyee()
        +marquerEchec()
    }
    class Rapport {
        +RapportId id
        +TypeRapport type
        +Periode periode
        +generer()
    }
    class Utilisateur {
        +UtilisateurId id
        +String nomUtilisateur
        +RoleUtilisateur role
        +changerRole(RoleUtilisateur)
    }
    class EntreeAudit {
        +AuditId id
        +String action
        +Instant horodatage
        +String correlationId
    }
    Site "1" --> "0..*" Equipement : regroupe
    Equipement "1" *-- "0..*" ServiceSupervise : héberge
    Equipement "1" --> "0..*" Metrique : produit
    Equipement "0..*" --> "0..*" RegleAlerte : surveillé par
    Equipement "1" --> "0..*" Alerte : concerne
    RegleAlerte "1" --> "0..*" Alerte : déclenche
    Alerte "1" --> "0..*" Notification : provoque
    Utilisateur "0..1" --> "0..*" Alerte : acquitte/résout
    Utilisateur "0..1" --> "0..*" EntreeAudit : auteur
    Rapport "0..*" --> "0..*" Equipement : synthétise
```

## 3. Modèle de conception

```mermaid
classDiagram
    direction LR
    class AlertController { <<boundary>> }
    class CollectionScheduler { <<boundary>> }
    class CollectionApplicationService { <<application service>> }
    class AlertApplicationService { <<application service>> }
    class NotificationApplicationService { <<application service>> }
    class Alerte { <<aggregate root>> }
    class RegleAlerte { <<aggregate root>> }
    class AlertRepository { <<interface>> }
    class MetricRepository { <<interface>> }
    class CollectionPort { <<interface>> }
    class NotificationPort { <<interface>> }
    class JpaAlertRepository { <<infrastructure>> }
    class JpaMetricRepository { <<infrastructure>> }
    class SnmpCollectionAdapter { <<infrastructure>> }
    class ZabbixCollectionAdapter { <<infrastructure>> }
    class SmtpNotificationAdapter { <<infrastructure>> }
    AlertController ..> AlertApplicationService
    CollectionScheduler ..> CollectionApplicationService
    CollectionApplicationService ..> CollectionPort
    CollectionApplicationService ..> MetricRepository
    CollectionApplicationService ..> AlertApplicationService
    AlertApplicationService ..> Alerte
    AlertApplicationService ..> RegleAlerte
    AlertApplicationService ..> AlertRepository
    AlertApplicationService ..> NotificationApplicationService
    NotificationApplicationService ..> NotificationPort
    JpaAlertRepository ..|> AlertRepository
    JpaMetricRepository ..|> MetricRepository
    SnmpCollectionAdapter ..|> CollectionPort
    ZabbixCollectionAdapter ..|> CollectionPort
    SmtpNotificationAdapter ..|> NotificationPort
```

## 4. Cycle d'état d'une alerte

```mermaid
stateDiagram-v2
    [*] --> Ouverte : seuil confirmé / ouvrir()
    Ouverte --> Acquittee : acquitter(opérateur)
    Ouverte --> Resolue : résoudre(opérateur)
    Acquittee --> Resolue : résoudre(opérateur)
    Resolue --> [*]
```

## 5. Activité — cycle d'un incident

```mermaid
flowchart TD
    start((Début)) --> read[Interroger la source]
    read --> reachable{Source joignable ?}
    reachable -- Non --> observe[Tracer l'échec sans secret]
    observe --> retry{Reprise autorisée ?}
    retry -- Oui --> read
    retry -- Non --> finish((Fin))
    reachable -- Oui --> normalize[Normaliser et horodater en UTC]
    normalize --> valid{Métrique valide ?}
    valid -- Non --> reject[Rejeter et tracer]
    reject --> finish
    valid -- Oui --> persist[Persister la métrique]
    persist --> threshold{Seuil confirmé ?}
    threshold -- Non --> finish
    threshold -- Oui --> duplicate{Alerte active identique ?}
    duplicate -- Oui --> update[Mettre à jour l'incident]
    update --> finish
    duplicate -- Non --> open[Ouvrir l'alerte]
    open --> notify[Notifier]
    notify --> acknowledge[Acquitter]
    acknowledge --> restored{Retour à la normale ?}
    restored -- Non --> acknowledge
    restored -- Oui --> resolve[Résoudre et auditer]
    resolve --> finish
```

## 6. Séquence — collecte et ouverture d'alerte

```mermaid
sequenceDiagram
    actor Scheduler as Planificateur
    participant Collection as Service de collecte
    participant Adapter as Adaptateur Zabbix/SNMP
    participant Source as Équipement ou Zabbix
    participant Metrics as Dépôt de métriques
    participant Alerting as Service d'alertes
    participant Alerts as Dépôt d'alertes
    participant Notify as Notifications
    Scheduler->>Collection: déclencherCollecte()
    Collection->>Adapter: collecter(équipement)
    Adapter->>Source: lire observations autorisées
    Source-->>Adapter: observations brutes
    Adapter-->>Collection: métriques normalisées
    loop Pour chaque métrique valide
        Collection->>Metrics: enregistrer(métrique UTC)
        Collection->>Alerting: évaluer(métrique)
        Alerting->>Alerts: rechercher alerte active
        alt Seuil confirmé et aucune alerte active
            Alerting->>Alerts: enregistrer alerte OUVERTE
            Alerting->>Notify: notifier(alerte)
        else Incident déjà représenté
            Alerting->>Alerts: mettre à jour l'incident
        else Seuil non dépassé
            Alerting-->>Collection: aucune alerte
        end
    end
```

## 7. Diagramme de composants

```mermaid
flowchart LR
    users["Administrateur / NOC / Lecteur"]
    sources["Équipements et serveurs"]
    smtp["Service SMTP"]
    subgraph transdic["Trans-DIC1"]
        web["Interface React"]
        api["API Spring Boot"]
        inventory["Inventaire"]
        monitoring["Collecte & Métriques"]
        alerting["Alertes"]
        notification["Notifications"]
        reporting["Rapports"]
        audit["Audit"]
        db[("PostgreSQL")]
    end
    users -->|HTTPS| web
    web -->|REST / WebSocket cible| api
    api --> inventory & monitoring & alerting & reporting
    inventory --> db
    monitoring --> db
    alerting --> db
    notification --> db
    reporting --> db
    audit --> db
    monitoring -->|SNMPv3 / Zabbix| sources
    alerting --> notification
    notification -->|SMTP| smtp
```

## 8. Déploiement local actuel

```mermaid
architecture-beta
    group client(cloud)[Poste utilisateur]
    group host(cloud)[Poste de développement]
    service browser(internet)[Navigateur] in client
    service vite(server)[Vite React port 5173] in host
    service spring(server)[Spring Boot port 8080] in host
    service postgres(database)[PostgreSQL 16 local] in host
    browser:R -- L:vite
    vite:R -- L:spring
    spring:R -- L:postgres
```

## 9. ERD cible

```mermaid
erDiagram
    SITES ||--o{ EQUIPMENTS : regroupe
    EQUIPMENTS ||--o{ SUPERVISED_SERVICES : heberge
    EQUIPMENTS ||--o{ METRICS : produit
    EQUIPMENTS ||--o{ EQUIPMENT_ALERT_RULES : affecte
    ALERT_RULES ||--o{ EQUIPMENT_ALERT_RULES : configure
    EQUIPMENTS ||--o{ ALERTS : concerne
    ALERT_RULES ||--o{ ALERTS : declenche
    ALERTS ||--o{ NOTIFICATIONS : provoque
    USERS o|--o{ ALERTS : traite
    USERS o|--o{ AUDIT_ENTRIES : effectue
    SITES { uuid id PK varchar code UK varchar name }
    EQUIPMENTS { uuid id PK uuid site_id FK varchar name varchar type }
    METRICS { uuid id PK uuid equipment_id FK varchar metric_type decimal value timestamp collected_at }
    ALERT_RULES { uuid id PK varchar metric_type decimal threshold varchar unit boolean active }
    EQUIPMENT_ALERT_RULES { uuid equipment_id PK, FK uuid alert_rule_id PK, FK }
    ALERTS { uuid id PK uuid equipment_id FK uuid alert_rule_id FK varchar status timestamp raised_at }
    NOTIFICATIONS { uuid id PK uuid alert_id FK varchar channel varchar delivery_status }
    USERS { uuid id PK varchar username UK varchar role boolean active }
    AUDIT_ENTRIES { uuid id PK uuid actor_id FK varchar action timestamp occurred_at }
```

## Fichiers sources

Les versions complètes et autonomes se trouvent dans [`docs/architecture/diagrams`](diagrams/README.md).
