-- Déduplication des alertes de seuil par service : une alerte active par (équipement, règle) pour
-- les mesures sans service (comportement inchangé), et par (équipement, règle, service) pour les
-- mesures des sondes. Les alertes de collecte (uq_alert_one_active_collection) ne changent pas.
DROP INDEX uq_alert_one_active_per_rule;

CREATE UNIQUE INDEX uq_alert_one_active_per_rule_equipment ON alert(equipment_id, rule_id)
    WHERE rule_id IS NOT NULL AND service_id IS NULL AND state IN ('OPEN', 'IN_PROGRESS');

CREATE UNIQUE INDEX uq_alert_one_active_per_rule_service ON alert(equipment_id, rule_id, service_id)
    WHERE rule_id IS NOT NULL AND service_id IS NOT NULL AND state IN ('OPEN', 'IN_PROGRESS');
