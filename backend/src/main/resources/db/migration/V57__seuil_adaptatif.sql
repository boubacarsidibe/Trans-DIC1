-- Seuil adaptatif (ADR 0049) : une règle à seuil peut apprendre, équipement par équipement, un
-- seuil qui suit les pointes habituelles de chaque créneau horaire.

ALTER TABLE alert_rule ADD COLUMN adaptive BOOLEAN NOT NULL DEFAULT FALSE;

-- Marge au-delà des pointes habituelles, en écarts-types (plus haut = moins d'alertes).
ALTER TABLE alert_rule ADD COLUMN adaptive_sensitivity NUMERIC(4, 2) NOT NULL DEFAULT 3;

-- Limite absolue facultative : au-delà (ou en deçà pour « inférieur à »), l'alerte s'ouvre quoi
-- qu'ait appris le modèle.
ALTER TABLE alert_rule ADD COLUMN adaptive_limit NUMERIC(20, 4);

ALTER TABLE alert_rule ADD CONSTRAINT ck_alert_rule_adaptive_operator
    CHECK (NOT adaptive OR operator <> 'EQUAL');
ALTER TABLE alert_rule ADD CONSTRAINT ck_alert_rule_adaptive_sensitivity
    CHECK (adaptive_sensitivity BETWEEN 1.5 AND 10);

-- Dernier seuil calculé par règle, équipement, service et indicateur, pour un créneau d'une heure.
-- Recalculé quand l'heure change ; sert aussi à montrer à l'administrateur ce qui a été appris.
CREATE TABLE adaptive_threshold (
    id UUID PRIMARY KEY,
    rule_id UUID NOT NULL REFERENCES alert_rule(id) ON DELETE CASCADE,
    equipment_id UUID NOT NULL REFERENCES equipment(id) ON DELETE CASCADE,
    service_id UUID REFERENCES monitored_service(id) ON DELETE CASCADE,
    metric_key VARCHAR(64) NOT NULL,
    slot_start TIMESTAMP WITH TIME ZONE NOT NULL,
    slot_label VARCHAR(40) NOT NULL,
    learned BOOLEAN NOT NULL,
    capped BOOLEAN NOT NULL DEFAULT FALSE,
    threshold NUMERIC(20, 4) NOT NULL,
    recovery_threshold NUMERIC(20, 4),
    normal_value NUMERIC(20, 4),
    deviation NUMERIC(20, 4),
    samples INTEGER NOT NULL,
    computed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_adaptive_threshold_samples CHECK (samples >= 0)
);

CREATE UNIQUE INDEX ux_adaptive_threshold_key ON adaptive_threshold (
    rule_id,
    equipment_id,
    COALESCE(service_id, '00000000-0000-0000-0000-000000000000'::uuid),
    metric_key
);
