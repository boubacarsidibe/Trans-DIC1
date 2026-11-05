-- Défense en profondeur (ADR 0020) : une seule alerte de prévision active par équipement et par
-- indicateur. La clé d'occurrence vaut « forecast:<équipement>:<indicateur>@<instant> » ; la partie
-- avant le « @ » identifie l'indicateur, l'instant distingue les alertes successives.
CREATE UNIQUE INDEX uq_alert_one_active_forecast ON alert (equipment_id, substring(occurrence_key from '^[^@]*@'))
    WHERE type = 'FORECAST' AND state IN ('OPEN', 'IN_PROGRESS');
