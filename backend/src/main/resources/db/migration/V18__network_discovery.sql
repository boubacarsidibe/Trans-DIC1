-- Découverte automatique d'équipements (FEAT-07) : une recherche SNMP sur une plage autorisée et
-- les équipements qui ont répondu, proposés sans création automatique. Aucun secret n'est stocké :
-- seulement la référence env:NOM de la communauté ou de la phrase SNMPv3.
CREATE TABLE discovery_scan (
    id                UUID PRIMARY KEY,
    cidr              VARCHAR(18)  NOT NULL,
    snmp_version      VARCHAR(8)   NOT NULL,
    credential_ref    VARCHAR(255) NOT NULL,
    security_identity VARCHAR(255),
    status            VARCHAR(16)  NOT NULL,
    requested_by      VARCHAR(64),
    hosts_total       INTEGER      NOT NULL,
    hosts_scanned     INTEGER      NOT NULL DEFAULT 0,
    error             VARCHAR(255),
    started_at        TIMESTAMPTZ  NOT NULL,
    finished_at       TIMESTAMPTZ,
    CONSTRAINT ck_discovery_scan_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'INTERRUPTED')),
    CONSTRAINT ck_discovery_scan_version CHECK (snmp_version IN ('V2C', 'V3'))
);

-- Une seule recherche à la fois sur la plateforme.
CREATE UNIQUE INDEX ux_discovery_scan_running ON discovery_scan (status) WHERE status = 'RUNNING';
CREATE INDEX idx_discovery_scan_started ON discovery_scan (started_at DESC);

CREATE TABLE discovery_candidate (
    id                    UUID PRIMARY KEY,
    scan_id               UUID         NOT NULL REFERENCES discovery_scan (id) ON DELETE CASCADE,
    address               VARCHAR(45)  NOT NULL,
    sys_name              VARCHAR(255),
    sys_descr             VARCHAR(512),
    sys_object_id         VARCHAR(128),
    suggested_type        VARCHAR(32)  NOT NULL,
    existing_equipment_id UUID REFERENCES equipment (id) ON DELETE SET NULL,
    discovered_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ux_discovery_candidate UNIQUE (scan_id, address)
);
