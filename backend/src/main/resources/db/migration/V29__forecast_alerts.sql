-- Alertes de prévision de saturation (ADR 0020) : un disque ou une mémoire sera plein dans peu de
-- jours si la tendance se poursuit. Comme les anomalies, elles n'ont pas de règle.
ALTER TABLE alert DROP CONSTRAINT ck_alert_type;
ALTER TABLE alert ADD CONSTRAINT ck_alert_type CHECK (type IN ('THRESHOLD', 'COLLECTION', 'ANOMALY', 'FORECAST'));
ALTER TABLE alert DROP CONSTRAINT ck_alert_type_rule_consistency;
ALTER TABLE alert ADD CONSTRAINT ck_alert_type_rule_consistency CHECK (
    (type = 'THRESHOLD' AND rule_id IS NOT NULL) OR (type IN ('COLLECTION', 'ANOMALY', 'FORECAST') AND rule_id IS NULL));
