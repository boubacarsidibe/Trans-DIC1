-- Demande de maintenance (ADR 0058). Planifier une maintenance coupe les notifications : c'est
-- reserve a l'administrateur. L'operateur NOC en fait la demande, motivee ; l'administrateur la
-- valide (la maintenance est alors creee, a son nom) ou la refuse, avec un motif. Table a part :
-- une demande en attente ne coupe jamais rien.
CREATE TABLE maintenance_demand (
    id UUID PRIMARY KEY,
    requester_id UUID NOT NULL REFERENCES app_user(id),
    site_id UUID REFERENCES site(id) ON DELETE CASCADE,
    equipment_id UUID REFERENCES equipment(id) ON DELETE CASCADE,
    -- Alerte d'origine, facultative : la demande et sa decision s'inscrivent dans sa chronologie.
    alert_id UUID REFERENCES alert(id) ON DELETE SET NULL,
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    decided_by_id UUID REFERENCES app_user(id),
    decided_at TIMESTAMPTZ,
    decision_comment VARCHAR(500),
    maintenance_id UUID REFERENCES maintenance(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_maintenance_demand_state CHECK (state IN ('PENDING', 'APPROVED', 'REFUSED')),
    CONSTRAINT ck_maintenance_demand_target CHECK ((site_id IS NOT NULL AND equipment_id IS NULL) OR (site_id IS NULL AND equipment_id IS NOT NULL)),
    CONSTRAINT ck_maintenance_demand_dates CHECK (end_at > start_at)
);

CREATE INDEX ix_maintenance_demand_pending ON maintenance_demand (created_at) WHERE state = 'PENDING';
