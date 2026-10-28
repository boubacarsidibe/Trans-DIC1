-- ADR-0004 : la table des mesures devient partitionnée par mois sur collected_at.
--   * clé primaire (collected_at, id), id entier issu de metric_id_seq (plus rapide qu'un UUID aléatoire) ;
--   * purge de la rétention par suppression de partition ;
--   * index (equipment_id, metric_key, collected_at DESC) pour les lectures, BRIN sur collected_at pour les balayages.
-- Les lignes existantes sont recopiées. Aucune clé étrangère ne pointe vers metric.

CREATE SEQUENCE metric_id_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE metric_new (
    id BIGINT NOT NULL DEFAULT nextval('metric_id_seq'),
    equipment_id UUID NOT NULL,
    metric_key VARCHAR(64) NOT NULL,
    metric_value NUMERIC(12, 4) NOT NULL,
    unit VARCHAR(24) NOT NULL,
    quality VARCHAR(24) NOT NULL,
    collected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    definition_id UUID,
    service_id UUID,
    agent_id UUID,
    PRIMARY KEY (collected_at, id)
) PARTITION BY RANGE (collected_at);

-- Crée la partition du mois de month_start (en UTC) sous la table parent. Idempotente ;
-- l'application l'appelle chaque jour pour préparer les mois à venir.
CREATE FUNCTION ensure_metric_partition(month_start DATE, parent TEXT DEFAULT 'metric') RETURNS TEXT
LANGUAGE plpgsql AS $$
DECLARE
    partition_name TEXT := 'metric_p' || to_char(month_start, 'YYYYMM');
BEGIN
    IF to_regclass(partition_name) IS NULL THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
            partition_name,
            parent,
            month_start::TIMESTAMP AT TIME ZONE 'UTC',
            (month_start + INTERVAL '1 month')::DATE::TIMESTAMP AT TIME ZONE 'UTC');
    END IF;
    RETURN partition_name;
END;
$$;

-- Filet de sécurité : une mesure hors des partitions prévues n'est jamais refusée.
CREATE TABLE metric_default PARTITION OF metric_new DEFAULT;

-- Les partitions doivent exister avant la copie : sinon les lignes iraient dans la partition par défaut
-- et empêcheraient de créer ensuite la partition de leur mois.
DO $$
DECLARE
    first_month DATE := COALESCE((SELECT date_trunc('month', min(collected_at) AT TIME ZONE 'UTC')::DATE FROM metric),
                                 date_trunc('month', now() AT TIME ZONE 'UTC')::DATE);
    last_month DATE := (date_trunc('month', now() AT TIME ZONE 'UTC') + INTERVAL '3 months')::DATE;
    month_start DATE := first_month;
BEGIN
    WHILE month_start <= last_month LOOP
        PERFORM ensure_metric_partition(month_start, 'metric_new');
        month_start := (month_start + INTERVAL '1 month')::DATE;
    END LOOP;
END;
$$;

INSERT INTO metric_new (equipment_id, metric_key, metric_value, unit, quality, collected_at, received_at, definition_id, service_id, agent_id)
SELECT equipment_id, metric_key, metric_value, unit, quality, collected_at, received_at, definition_id, service_id, agent_id
FROM metric
ORDER BY collected_at;

DROP TABLE metric;
ALTER TABLE metric_new RENAME TO metric;
ALTER TABLE metric RENAME CONSTRAINT metric_new_pkey TO metric_pkey;
ALTER SEQUENCE metric_id_seq OWNED BY metric.id;

ALTER TABLE metric ADD CONSTRAINT fk_metric_equipment_id FOREIGN KEY (equipment_id) REFERENCES equipment(id);
ALTER TABLE metric ADD CONSTRAINT fk_metric_definition_id FOREIGN KEY (definition_id) REFERENCES metric_definition(id);
ALTER TABLE metric ADD CONSTRAINT fk_metric_service_id FOREIGN KEY (service_id) REFERENCES monitored_service(id);
ALTER TABLE metric ADD CONSTRAINT fk_metric_agent_id FOREIGN KEY (agent_id) REFERENCES collection_agent(id);
ALTER TABLE metric ADD CONSTRAINT ck_metric_quality CHECK (quality IN ('GOOD', 'DEGRADED', 'BAD'));

CREATE INDEX idx_metric_equipment_collected ON metric(equipment_id, metric_key, collected_at DESC);
CREATE INDEX idx_metric_collected_at_brin ON metric USING BRIN (collected_at);
CREATE INDEX idx_metric_definition_id ON metric(definition_id);
CREATE INDEX idx_metric_service_id ON metric(service_id);
CREATE INDEX idx_metric_agent_id ON metric(agent_id);
