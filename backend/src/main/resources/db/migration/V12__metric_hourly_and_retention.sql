-- ADR-0004 : agrégats horaires et purge des partitions de mesures brutes.
-- Les mesures brutes sont gardées 30 jours, les agrégats horaires 13 mois. Une partition de mesures
-- n'est supprimée qu'une fois entièrement expirée ET déjà agrégée : l'historique n'est jamais perdu.

CREATE TABLE metric_hourly (
    equipment_id UUID NOT NULL,
    metric_key VARCHAR(64) NOT NULL,
    hour_start TIMESTAMP WITH TIME ZONE NOT NULL,
    unit VARCHAR(24) NOT NULL,
    sample_count INTEGER NOT NULL,
    min_value NUMERIC(12, 4) NOT NULL,
    max_value NUMERIC(12, 4) NOT NULL,
    avg_value NUMERIC(12, 4) NOT NULL,
    PRIMARY KEY (equipment_id, metric_key, hour_start),
    CONSTRAINT fk_metric_hourly_equipment_id FOREIGN KEY (equipment_id) REFERENCES equipment(id) ON DELETE CASCADE,
    CONSTRAINT ck_metric_hourly_sample_count CHECK (sample_count > 0),
    CONSTRAINT ck_metric_hourly_min_max CHECK (min_value <= max_value)
);
CREATE INDEX idx_metric_hourly_hour_start ON metric_hourly(hour_start);

-- Supprime les partitions mensuelles de metric dont la borne haute est antérieure à cutoff et
-- déjà couverte par les agrégats horaires. Retourne les noms supprimés. La partition par défaut
-- n'est jamais supprimée.
CREATE FUNCTION drop_expired_metric_partitions(cutoff TIMESTAMP WITH TIME ZONE) RETURNS SETOF TEXT
LANGUAGE plpgsql AS $fn$
DECLARE
    part RECORD;
    upper_bound TIMESTAMP WITH TIME ZONE;
    aggregated_until TIMESTAMP WITH TIME ZONE;
BEGIN
    SELECT max(hour_start) + INTERVAL '1 hour' INTO aggregated_until FROM metric_hourly;
    IF aggregated_until IS NULL THEN
        RETURN;
    END IF;
    FOR part IN
        SELECT c.oid::regclass::TEXT AS name, pg_get_expr(c.relpartbound, c.oid) AS bound
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        WHERE i.inhparent = 'metric'::regclass
          AND pg_get_expr(c.relpartbound, c.oid) <> 'DEFAULT'
    LOOP
        upper_bound := substring(part.bound FROM $re$TO \('([^']+)'\)$re$)::TIMESTAMP WITH TIME ZONE;
        IF upper_bound <= cutoff AND upper_bound <= aggregated_until THEN
            EXECUTE format('DROP TABLE %s', part.name);
            RETURN NEXT part.name;
        END IF;
    END LOOP;
END;
$fn$;
