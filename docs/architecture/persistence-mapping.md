# Modèle persistant : correspondance avec le diagramme

Le diagramme de référence est [persistence-model-uml.mmd](diagrams/02-domain/persistence-model-uml.mmd). Il est écrit en français ; le code (classes, tables, API) est en anglais. Ce document relie les deux.

## Modules

| Module Java | Contenu | Dépend de |
|---|---|---|
| `security` | `AppUser`, `UserSession`, `PasswordResetToken` | rien |
| `inventory` | `Site`, `Equipment`, `MonitoredService`, `CollectionConfiguration`, `MetricDefinition`, `CollectionAgent` | `audit` (service) |
| `monitoring` | `Metric` | `inventory.domain` |
| `access` | `SiteAccess`, `EquipmentAccess` | `security.domain`, `inventory.domain` |
| `alerting` | `AlertRule`, `RuleSiteScope`, `RuleEquipmentScope`, `Alert`, `AlertAssignment`, `AlertEvent`, `Maintenance` | `security.domain`, `inventory.domain` |
| `reporting` | `NotificationPreference`, `Notification`, `NotificationAttempt`, `Report`, `ReportSchedule`, `ScheduleRecipient`, `ScheduleSiteScope`, `ScheduleEquipmentScope` | `security.domain`, `inventory.domain`, `alerting.domain` |
| `audit` | `AuditEntry` | `security` |

Ces dépendances sont vérifiées par ArchUnit (voir [archunit.md](archunit.md)). `access` est un module à part pour éviter un cycle `security` ↔ `inventory`.

## Entités

| Diagramme | Classe | Table |
|---|---|---|
| Utilisateur | `AppUser` | `app_user` |
| SessionUtilisateur | `UserSession` | `user_session` |
| JetonReinitialisation | `PasswordResetToken` | `password_reset_token` |
| AccesSite | `SiteAccess` | `site_access` |
| AccesEquipement | `EquipmentAccess` | `equipment_access` |
| Site | `Site` | `site` |
| Equipement | `Equipment` | `equipment` |
| ServiceSupervise | `MonitoredService` | `monitored_service` |
| ConfigurationCollecte | `CollectionConfiguration` | `collection_configuration` |
| DefinitionMetrique | `MetricDefinition` | `metric_definition` |
| AgentCollecte | `CollectionAgent` | `collection_agent` |
| Metrique | `Metric` | `metric` |
| RegleAlerte | `AlertRule` | `alert_rule` |
| RegleSite | `RuleSiteScope` | `rule_site_scope` |
| RegleEquipement | `RuleEquipmentScope` | `rule_equipment_scope` |
| Alerte | `Alert` | `alert` |
| AffectationAlerte | `AlertAssignment` | `alert_assignment` |
| EvenementAlerte | `AlertEvent` | `alert_event` |
| Maintenance | `Maintenance` | `maintenance` |
| PreferenceNotification | `NotificationPreference` | `notification_preference` |
| Notification | `Notification` | `notification` |
| TentativeNotification | `NotificationAttempt` | `notification_attempt` |
| Rapport | `Report` | `report` |
| PlanificationRapport | `ReportSchedule` | `report_schedule` |
| DestinataireRapport | `ScheduleRecipient` | `schedule_recipient` |
| PlanificationRapportSite | `ScheduleSiteScope` | `schedule_site_scope` |
| PlanificationRapportEquipement | `ScheduleEquipmentScope` | `schedule_equipment_scope` |
| EntreeAudit | `AuditEntry` | `audit_entry` |

## États et énumérations

| Diagramme | Énumération | Valeurs |
|---|---|---|
| EtatAlerte | `AlertState` | `OPEN` (OUVERTE), `IN_PROGRESS` (PRISE_EN_CHARGE), `RESOLVED` (RESOLUE), `ARCHIVED` (ARCHIVEE), `DELETED` (SUPPRIMEE) |
| TypeEquipement | `EquipmentType` | `SERVER`, `ROUTER`, `SWITCH`, `VIRTUAL_MACHINE`, `WIFI_ACCESS_POINT` — des **valeurs**, pas des sous-classes |
| StatutEquipement | `EquipmentStatus` | `UP`, `DOWN`, `DEGRADED`, `MAINTENANCE`, `UNKNOWN` |
| StatutSite | `SiteStatus` | `ACTIVE`, `INACTIVE` |
| Severite | `Severity` | `INFO`, `WARNING`, `CRITICAL` |
| TypeAlerte | `AlertType` | `THRESHOLD` (SEUIL), `COLLECTION` (COLLECTE) |
| TypeResolution | `ResolutionType` | `MANUAL`, `AUTOMATIC` |
| EtatMaintenance | `MaintenanceState` | `PLANNED`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED` |
| ModeCollecte | `CollectionMode` | `AGENT`, `SNMP` |
| VersionSnmp | `SnmpVersion` | `V2C`, `V3` |
| StatutAgent | `AgentStatus` | `PENDING`, `ACTIVE`, `INACTIVE`, `REVOKED` |
| QualiteMesure | `MeasurementQuality` | `GOOD`, `DEGRADED`, `BAD` |
| RoleUtilisateur | `Role` | `ADMIN`, `NOC_OPERATOR`, `VIEWER` |

Les autres énumérations (`ServiceType`, `ValueType`, `ComparisonOperator`, `AlertEventType`, `NotificationChannel`, `NotificationState`, `SendResult`, `ReportType`, `ReportState`, `ReportFormat`, `ReportFrequency`) sont dans le package `domain` de leur module. Chacune est stockée en texte et protégée par une contrainte `CHECK` en base.

## Écarts assumés avec le diagramme

- **Noms en anglais** (décision d'équipe) : la table ci-dessus fait foi.
- `Equipment.inventoryCode` et `Equipment.fictitious` n'existent pas dans le diagramme : ils viennent de l'API déjà livrée (code d'inventaire unique, données de démonstration).
- `Metric.key` est conservé en plus de la relation vers `MetricDefinition` (facultative) pour les mesures déjà enregistrées.
- `AuditEntry.username` copie le nom du compte à l'instant de l'action ; la relation `author` peut devenir vide si le compte est supprimé.
- Les durées (`Duration`) sont stockées en secondes (`*_seconds`, `BIGINT`) pour rester indépendantes du moteur.

## Où chaque règle est garantie

| Règle du diagramme | Garantie par |
|---|---|
| Une maintenance cible exactement un site ou un équipement | `CHECK` `ck_maintenance_exactly_one_target` |
| Une notification vient d'une alerte ou d'un rapport, jamais des deux | `CHECK` `ck_notification_exactly_one_origin` |
| `THRESHOLD` exige une règle, `COLLECTION` n'en a pas | `CHECK` `ck_alert_type_rule_consistency` |
| Mode `SNMP` exige une version ; mode `AGENT` sans paramètre SNMP | `CHECK` sur `collection_configuration` |
| Au plus un agent actif par équipement | index unique partiel `uq_collection_agent_one_active` (PostgreSQL) |
| Une seule alerte active par couple équipement-règle, ou par équipement pour `COLLECTION` | index uniques partiels `uq_alert_one_active_*` (PostgreSQL) |
| Au plus une affectation active par alerte | index unique partiel `uq_alert_assignment_one_active` (PostgreSQL) |
| Une préférence de notification par utilisateur | contrainte `UNIQUE (user_id)` |
| La portée d'une planification contient au moins un site ou un équipement | **service** (non exprimable en contrainte portant sur une seule table) |
| Compositions (sessions, services, configurations, événements, tentatives…) | clés étrangères `ON DELETE CASCADE` |

## Secrets de collecte

`CollectionConfiguration` ne stocke que des **références** vers un gestionnaire de secrets (`communityReference`, `securityIdentity`, `secretReference`), jamais les secrets eux-mêmes. Ces champs portent `@JsonIgnore`, aucune entité ne redéfinit `toString()` et les contrôleurs n'exposent aucune entité (règle ArchUnit). Ces propriétés sont testées dans `PersistenceModelTests`.

## Migrations

- `db/migration/V7__persistent_model.sql` : tables, clés, `CHECK` et index, plus l'évolution additive des tables existantes (aucune donnée supprimée).
- `db/migration/V8__partial_unique_indexes.sql` : index uniques partiels (PostgreSQL). Depuis l'ADR-0003, un seul dossier de migrations est appliqué en développement, en test (Testcontainers) et en production.
- V9 partitionne la table `metric` par mois (ADR-0004) ; V10 ajoute `family_id` et `replaced_by_id` aux sessions (ADR-0005) ; V11 ajoute `alert_rule.recovery_threshold` (hystérésis) ; V12 ajoute `metric_hourly` et la fonction de purge des partitions expirées. La prochaine migration est la V13.
