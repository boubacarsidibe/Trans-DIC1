-- Nouveau type d'alerte : un équipement qui se comporte autrement que ses semblables (même type,
-- même site). Comme les anomalies et les prévisions, il ne s'appuie sur aucune règle.
ALTER TABLE alert DROP CONSTRAINT ck_alert_type;
ALTER TABLE alert ADD CONSTRAINT ck_alert_type
    CHECK (type IN ('THRESHOLD', 'COLLECTION', 'ANOMALY', 'FORECAST', 'EVENT', 'PEER'));

ALTER TABLE alert DROP CONSTRAINT ck_alert_type_rule_consistency;
ALTER TABLE alert ADD CONSTRAINT ck_alert_type_rule_consistency CHECK (
    (type = 'THRESHOLD' AND rule_id IS NOT NULL AND event_rule_id IS NULL)
    OR (type = 'EVENT' AND rule_id IS NULL AND event_rule_id IS NOT NULL)
    OR (type IN ('COLLECTION', 'ANOMALY', 'FORECAST', 'PEER') AND rule_id IS NULL AND event_rule_id IS NULL));
