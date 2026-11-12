-- Voisins LLDP (ADR 0034) : ce que chaque équipement dit voir au bout de ses ports.
-- La topologie reste saisie par un administrateur ; ces lignes ne font que la proposer.
CREATE TABLE lldp_neighbour (
    id UUID PRIMARY KEY,
    equipment_id UUID NOT NULL REFERENCES equipment(id) ON DELETE CASCADE,
    -- Port local tel que LLDP le numérote, et son nom si l'équipement le donne.
    local_port_index INTEGER NOT NULL,
    local_port VARCHAR(120),
    remote_chassis_id VARCHAR(120) NOT NULL,
    remote_port_id VARCHAR(120) NOT NULL,
    remote_port_description VARCHAR(255),
    remote_system_name VARCHAR(255),
    remote_system_description VARCHAR(500),
    remote_management_address VARCHAR(45),
    -- Équipement de l'inventaire reconnu au bout du câble, s'il a pu l'être.
    matched_equipment_id UUID REFERENCES equipment(id) ON DELETE SET NULL,
    match_reason VARCHAR(32),
    -- Voisin écarté par un administrateur : il ne sera plus proposé.
    ignored BOOLEAN NOT NULL DEFAULT FALSE,
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_lldp_neighbour
        UNIQUE (equipment_id, local_port_index, remote_chassis_id, remote_port_id)
);

CREATE INDEX idx_lldp_neighbour_equipment ON lldp_neighbour(equipment_id);
CREATE INDEX idx_lldp_neighbour_matched ON lldp_neighbour(matched_equipment_id);
