-- Événements réseau reçus (ADR 0021) : traps SNMP et messages syslog d'équipements inventoriés.
-- Une source inconnue n'est jamais stockée. Les détails sont tronqués à 4 Kio par le récepteur.
CREATE TABLE network_event (
    id UUID PRIMARY KEY,
    equipment_id UUID NOT NULL REFERENCES equipment(id) ON DELETE CASCADE,
    source VARCHAR(16) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    source_address VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    details TEXT,
    trap_oid VARCHAR(255),
    hostname VARCHAR(255),
    app_name VARCHAR(64),
    facility SMALLINT,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_network_event_source CHECK (source IN ('SNMP_TRAP', 'SYSLOG')),
    CONSTRAINT ck_network_event_severity CHECK (severity IN
        ('EMERGENCY', 'ALERT', 'CRITICAL', 'ERROR', 'WARNING', 'NOTICE', 'INFO', 'DEBUG')),
    CONSTRAINT ck_network_event_details CHECK (details IS NULL OR length(details) <= 4096),
    CONSTRAINT ck_network_event_facility CHECK (facility IS NULL OR facility BETWEEN 0 AND 23)
);

CREATE INDEX idx_network_event_equipment_received ON network_event(equipment_id, received_at DESC);
CREATE INDEX idx_network_event_received ON network_event(received_at);

-- Règles d'événements : un événement qui correspond ouvre une alerte EVENT ; un événement de retour
-- (resolve_oid / resolve_regex) la résout. Sans portée, la règle vaut pour tous les équipements.
CREATE TABLE event_rule (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL UNIQUE,
    source VARCHAR(16),
    min_severity VARCHAR(16),
    oid_prefix VARCHAR(255),
    message_regex VARCHAR(200),
    resolve_oid VARCHAR(255),
    resolve_regex VARCHAR(200),
    alert_severity VARCHAR(16) NOT NULL,
    dedup_window_seconds INTEGER NOT NULL DEFAULT 300,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_event_rule_source CHECK (source IS NULL OR source IN ('SNMP_TRAP', 'SYSLOG')),
    CONSTRAINT ck_event_rule_min_severity CHECK (min_severity IS NULL OR min_severity IN
        ('EMERGENCY', 'ALERT', 'CRITICAL', 'ERROR', 'WARNING', 'NOTICE', 'INFO', 'DEBUG')),
    CONSTRAINT ck_event_rule_alert_severity CHECK (alert_severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_event_rule_dedup CHECK (dedup_window_seconds BETWEEN 0 AND 86400)
);

CREATE TABLE event_rule_site (
    rule_id UUID NOT NULL REFERENCES event_rule(id) ON DELETE CASCADE,
    site_id UUID NOT NULL REFERENCES site(id) ON DELETE CASCADE,
    PRIMARY KEY (rule_id, site_id)
);

CREATE TABLE event_rule_equipment (
    rule_id UUID NOT NULL REFERENCES event_rule(id) ON DELETE CASCADE,
    equipment_id UUID NOT NULL REFERENCES equipment(id) ON DELETE CASCADE,
    PRIMARY KEY (rule_id, equipment_id)
);

-- Alertes EVENT : rattachées à une règle d'événement, jamais à une règle de seuil.
ALTER TABLE alert ADD COLUMN event_rule_id UUID REFERENCES event_rule(id);
ALTER TABLE alert DROP CONSTRAINT ck_alert_type;
ALTER TABLE alert ADD CONSTRAINT ck_alert_type
    CHECK (type IN ('THRESHOLD', 'COLLECTION', 'ANOMALY', 'FORECAST', 'EVENT'));
ALTER TABLE alert DROP CONSTRAINT ck_alert_type_rule_consistency;
ALTER TABLE alert ADD CONSTRAINT ck_alert_type_rule_consistency CHECK (
    (type = 'THRESHOLD' AND rule_id IS NOT NULL AND event_rule_id IS NULL)
    OR (type = 'EVENT' AND rule_id IS NULL AND event_rule_id IS NOT NULL)
    OR (type IN ('COLLECTION', 'ANOMALY', 'FORECAST') AND rule_id IS NULL AND event_rule_id IS NULL));

-- Déduplication : une seule alerte EVENT active par (équipement, règle d'événement).
CREATE UNIQUE INDEX uq_alert_one_active_per_event_rule ON alert(equipment_id, event_rule_id)
    WHERE event_rule_id IS NOT NULL AND state IN ('OPEN', 'IN_PROGRESS');

-- Règles prêtes à l'emploi, créées inactives et sans portée : l'administrateur les active.
INSERT INTO event_rule (id, name, source, min_severity, oid_prefix, message_regex, resolve_oid,
                        resolve_regex, alert_severity, dedup_window_seconds, active)
VALUES
    (gen_random_uuid(), 'Interface coupée (linkDown)', 'SNMP_TRAP', NULL, '1.3.6.1.6.3.1.1.5.3', NULL,
     '1.3.6.1.6.3.1.1.5.4', NULL, 'CRITICAL', 300, FALSE),
    (gen_random_uuid(), 'Échec d''authentification SNMP', 'SNMP_TRAP', NULL, '1.3.6.1.6.3.1.1.5.5',
     NULL, NULL, NULL, 'WARNING', 300, FALSE),
    (gen_random_uuid(), 'Erreur système (syslog)', 'SYSLOG', 'ERROR', NULL, NULL, NULL, NULL,
     'WARNING', 300, FALSE)
ON CONFLICT (name) DO NOTHING;
