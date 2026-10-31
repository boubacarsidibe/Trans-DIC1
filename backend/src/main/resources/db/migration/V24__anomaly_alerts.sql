-- Alertes d'anomalie : un indicateur s'écarte de son comportement habituel (même jour, même heure),
-- sans règle de seuil.
ALTER TABLE alert DROP CONSTRAINT ck_alert_type;
ALTER TABLE alert ADD CONSTRAINT ck_alert_type CHECK (type IN ('THRESHOLD', 'COLLECTION', 'ANOMALY'));
ALTER TABLE alert DROP CONSTRAINT ck_alert_type_rule_consistency;
ALTER TABLE alert ADD CONSTRAINT ck_alert_type_rule_consistency CHECK (
    (type = 'THRESHOLD' AND rule_id IS NOT NULL) OR (type IN ('COLLECTION', 'ANOMALY') AND rule_id IS NULL));
