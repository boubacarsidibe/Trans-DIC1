-- Découverte des interfaces réseau par SNMP (IF-MIB) : une ligne par port d'un équipement.
-- Les compteurs lus au passage précédent sont gardés ici pour calculer un débit : la table
-- IF-MIB ne donne que des compteurs cumulés depuis le démarrage de l'équipement.
CREATE TABLE equipment_interface (
    id UUID PRIMARY KEY,
    equipment_id UUID NOT NULL REFERENCES equipment(id) ON DELETE CASCADE,
    if_index INTEGER NOT NULL,
    name VARCHAR(120) NOT NULL,
    alias VARCHAR(255),
    if_type INTEGER,
    speed_bps BIGINT,
    admin_up BOOLEAN,
    oper_up BOOLEAN,
    monitored BOOLEAN NOT NULL DEFAULT TRUE,
    in_octets NUMERIC(24, 0),
    out_octets NUMERIC(24, 0),
    in_errors NUMERIC(24, 0),
    out_errors NUMERIC(24, 0),
    in_discards NUMERIC(24, 0),
    out_discards NUMERIC(24, 0),
    counters_at TIMESTAMP WITH TIME ZONE,
    discovered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_equipment_interface UNIQUE (equipment_id, if_index),
    CONSTRAINT ck_equipment_interface_index CHECK (if_index >= 0)
);

CREATE INDEX idx_equipment_interface_equipment ON equipment_interface(equipment_id);

-- La découverte est un choix par configuration de collecte : inactive par défaut, pour ne pas
-- multiplier d'un coup les mesures des parcs déjà en place.
ALTER TABLE collection_configuration
    ADD COLUMN discover_interfaces BOOLEAN NOT NULL DEFAULT FALSE;

-- Règles prêtes à l'emploi, inactives et sans périmètre, comme celles des sondes (V27).
INSERT INTO alert_rule (id, name, metric_type, operator, threshold, recovery_threshold, unit,
                        confirmation_duration_seconds, initial_severity, active, all_equipment)
VALUES
    (gen_random_uuid(), 'Lien réseau saturé', 'net.usage[*]', 'GREATER_THAN', 90, 80, '%', 300, 'WARNING', FALSE, TRUE),
    (gen_random_uuid(), 'Erreurs sur un lien réseau', 'net.errors[*]', 'GREATER_THAN', 10, 1, '/s', 300, 'WARNING', FALSE, TRUE)
ON CONFLICT (name) DO NOTHING;
