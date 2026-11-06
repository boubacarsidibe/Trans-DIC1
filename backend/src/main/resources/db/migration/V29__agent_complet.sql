-- Agent complet (chantier 1) : mesures en octets et en bit/s, alertes par disque ou par carte,
-- règles sur tous les équipements, inventaire et top des processus.

-- NUMERIC(12, 4) plafonne à 99 999 999 : un disque de 1 To ou un lien à 1 Gbit/s débordent.
ALTER TABLE metric ALTER COLUMN metric_value TYPE NUMERIC(20, 4);
ALTER TABLE metric_hourly ALTER COLUMN min_value TYPE NUMERIC(20, 4);
ALTER TABLE metric_hourly ALTER COLUMN max_value TYPE NUMERIC(20, 4);
ALTER TABLE metric_hourly ALTER COLUMN avg_value TYPE NUMERIC(20, 4);
ALTER TABLE alert_rule ALTER COLUMN threshold TYPE NUMERIC(20, 4);
ALTER TABLE alert_rule ALTER COLUMN recovery_threshold TYPE NUMERIC(20, 4);

-- Portée « tous les équipements » : une règle utile sans configuration par site.
ALTER TABLE alert_rule ADD COLUMN all_equipment BOOLEAN NOT NULL DEFAULT FALSE;

-- Une alerte par instance : deux disques pleins sur un serveur font deux alertes distinctes.
ALTER TABLE alert ADD COLUMN metric_instance VARCHAR(64);
ALTER TABLE alert ADD COLUMN context VARCHAR(20000);

DROP INDEX uq_alert_one_active_per_rule_equipment;
CREATE UNIQUE INDEX uq_alert_one_active_per_rule_equipment
    ON alert(equipment_id, rule_id, COALESCE(metric_instance, ''))
    WHERE rule_id IS NOT NULL AND service_id IS NULL AND state IN ('OPEN', 'IN_PROGRESS');

-- Dernier inventaire et top des processus d'un équipement : remplacé à chaque envoi, sans historique.
CREATE TABLE agent_snapshot (
    equipment_id UUID PRIMARY KEY REFERENCES equipment(id) ON DELETE CASCADE,
    payload VARCHAR(20000) NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Règles livrées actives : l'agent installé, les alertes utiles fonctionnent sans réglage.
-- Le seuil de retour évite qu'une valeur proche du seuil fasse clignoter l'alerte.
INSERT INTO alert_rule (id, name, metric_type, operator, threshold, recovery_threshold, unit,
                        confirmation_duration_seconds, initial_severity, active, all_equipment)
VALUES
    (gen_random_uuid(), 'Disque presque plein', 'disk.usage[*]', 'GREATER_THAN', 90, 88, '%', 300, 'WARNING', TRUE, TRUE),
    (gen_random_uuid(), 'Disque saturé', 'disk.usage[*]', 'GREATER_THAN', 97, 96, '%', 300, 'CRITICAL', TRUE, TRUE),
    (gen_random_uuid(), 'Mémoire saturée', 'memory.usage', 'GREATER_THAN', 95, 90, '%', 600, 'WARNING', TRUE, TRUE),
    (gen_random_uuid(), 'Processeur saturé', 'cpu.usage', 'GREATER_THAN', 95, 85, '%', 900, 'WARNING', TRUE, TRUE),
    (gen_random_uuid(), 'Carte réseau coupée', 'net.up[*]', 'LESS_THAN', 1, NULL, NULL, 120, 'WARNING', TRUE, TRUE),
    (gen_random_uuid(), 'Service arrêté', 'service.up[*]', 'LESS_THAN', 1, NULL, NULL, 120, 'CRITICAL', TRUE, TRUE)
ON CONFLICT (name) DO NOTHING;
