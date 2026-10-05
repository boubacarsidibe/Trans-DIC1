# Modèle de domaine initial

Ce document matérialise les principaux objets du backlog. Il ne remplace pas les migrations : il fixe le vocabulaire partagé avant l'implémentation des entités.

```mermaid
classDiagram
  class Site {
    +UUID id
    +String name
    +String code
  }
  class Device {
    +UUID id
    +String name
    +String managementAddress
    +DeviceType type
    +DeviceStatus status
  }
  class Metric {
    +UUID id
    +String key
    +Instant collectedAt
    +Decimal value
    +String unit
  }
  class Alert {
    +UUID id
    +Severity severity
    +AlertStatus status
    +Instant raisedAt
    +Instant acknowledgedAt
    +Instant resolvedAt
  }
  class Notification {
    +UUID id
    +Channel channel
    +DeliveryStatus status
    +Instant sentAt
  }
  class User {
    +UUID id
    +String username
    +Role role
  }
  Site "1" --> "many" Device
  Device "1" --> "many" Metric
  Device "1" --> "many" Alert
  Alert "1" --> "many" Notification
  User "0..1" --> "many" Alert : acquitte/résout
```

## Cycle d'une alerte

```mermaid
sequenceDiagram
  participant C as Collecteur
  participant API as Backend
  participant DB as PostgreSQL
  participant N as Notification
  participant O as Opérateur NOC
  C->>API: mesure normalisée
  API->>DB: persister métrique
  API->>API: évaluer seuil et durée
  alt anomalie confirmée
    API->>DB: ouvrir ou mettre à jour alerte
    API->>N: demander notification
    N-->>DB: enregistrer résultat d'envoi
    O->>API: acquitter puis résoudre
    API->>DB: tracer transitions et audit
  end
```

## Invariants initiaux

- Une métrique appartient à exactement un équipement et porte un horodatage UTC.
- Une seule alerte ouverte existe pour un même équipement, une même règle et une même occurrence.
- L'acquittement et la résolution enregistrent l'auteur et la date.
- La notification ne contient jamais de secret de collecte.
- Les suppressions fonctionnelles sensibles sont auditées.
