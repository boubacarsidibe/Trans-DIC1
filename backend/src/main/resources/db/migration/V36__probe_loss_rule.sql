-- La sonde ICMP envoie plusieurs paquets et mesure la perte (probe.loss) et la gigue (probe.jitter).
-- Règle prête à l'emploi, créée inactive et sans périmètre, comme celles de V27.
INSERT INTO alert_rule (id, name, metric_type, operator, threshold, recovery_threshold, unit,
                        confirmation_duration_seconds, initial_severity, active)
VALUES
    (gen_random_uuid(), 'Perte de paquets', 'probe.loss', 'GREATER_THAN', 20, 10, '%', 300, 'WARNING', FALSE)
ON CONFLICT (name) DO NOTHING;
