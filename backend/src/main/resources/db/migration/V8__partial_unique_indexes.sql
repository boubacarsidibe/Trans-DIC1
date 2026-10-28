-- Index uniques partiels : PostgreSQL uniquement (H2, utilisé par les tests, ne les supporte pas).
-- Ce dossier n'est chargé que si spring.flyway.locations l'inclut.

CREATE UNIQUE INDEX uq_collection_agent_one_active ON collection_agent(equipment_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uq_alert_one_active_per_rule ON alert(equipment_id, rule_id) WHERE rule_id IS NOT NULL AND state IN ('OPEN', 'IN_PROGRESS');
CREATE UNIQUE INDEX uq_alert_one_active_collection ON alert(equipment_id) WHERE type = 'COLLECTION' AND state IN ('OPEN', 'IN_PROGRESS');
CREATE UNIQUE INDEX uq_alert_assignment_one_active ON alert_assignment(alert_id) WHERE ended_at IS NULL;
