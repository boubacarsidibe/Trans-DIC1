-- Absence de donnée : un indicateur qui arrivait régulièrement cesse d'arriver, alors que
-- l'équipement répond par ailleurs. Distinct de la perte de collecte, qui vaut pour tout
-- l'équipement et compte dans la disponibilité.
ALTER TABLE alert DROP CONSTRAINT ck_alert_type;
ALTER TABLE alert ADD CONSTRAINT ck_alert_type
    CHECK (type IN ('THRESHOLD', 'COLLECTION', 'ANOMALY', 'FORECAST', 'EVENT', 'PEER', 'NO_DATA'));

ALTER TABLE alert DROP CONSTRAINT ck_alert_type_rule_consistency;
ALTER TABLE alert ADD CONSTRAINT ck_alert_type_rule_consistency CHECK (
    (type = 'THRESHOLD' AND rule_id IS NOT NULL AND event_rule_id IS NULL)
    OR (type = 'EVENT' AND rule_id IS NULL AND event_rule_id IS NOT NULL)
    OR (type IN ('COLLECTION', 'ANOMALY', 'FORECAST', 'PEER', 'NO_DATA')
        AND rule_id IS NULL AND event_rule_id IS NULL));
