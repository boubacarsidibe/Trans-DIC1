-- Flux réseau NetFlow (lot 4, ADR 0032) : qui parle à qui, et combien.
-- Rien n'est conservé flux par flux : tout est agrégé par minute avant d'entrer en base.

-- Totaux par équipement et par minute : exacts même quand le détail est plafonné.
CREATE TABLE flow_bucket (
    equipment_id UUID NOT NULL REFERENCES equipment(id) ON DELETE CASCADE,
    bucket_start TIMESTAMP WITH TIME ZONE NOT NULL,
    bytes BIGINT NOT NULL,
    packets BIGINT NOT NULL,
    flows BIGINT NOT NULL,
    -- Conversations distinctes vues dans la minute, avant tout plafonnement.
    conversations INTEGER NOT NULL,
    -- Vrai si le détail a été plafonné : les totaux restent justes, pas la liste.
    truncated BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (equipment_id, bucket_start),
    CONSTRAINT ck_flow_bucket_positive CHECK (bytes >= 0 AND packets >= 0 AND flows >= 0)
);

-- Détail : les plus grosses conversations de la minute, au plus quelques centaines par équipement.
-- Les adresses sont des données personnelles : la conservation est volontairement courte.
CREATE TABLE flow_aggregate (
    equipment_id UUID NOT NULL REFERENCES equipment(id) ON DELETE CASCADE,
    bucket_start TIMESTAMP WITH TIME ZONE NOT NULL,
    source_address VARCHAR(45) NOT NULL,
    destination_address VARCHAR(45) NOT NULL,
    protocol SMALLINT NOT NULL,
    destination_port INTEGER NOT NULL,
    bytes BIGINT NOT NULL,
    packets BIGINT NOT NULL,
    flows BIGINT NOT NULL,
    PRIMARY KEY (equipment_id, bucket_start, source_address, destination_address, protocol,
                 destination_port)
);

CREATE INDEX idx_flow_bucket_start ON flow_bucket(bucket_start);
CREATE INDEX idx_flow_aggregate_start ON flow_aggregate(bucket_start);
CREATE INDEX idx_flow_aggregate_equipment ON flow_aggregate(equipment_id, bucket_start DESC);
