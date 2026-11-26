-- Connecteurs d'hyperviseur et machines virtuelles vues sans agent (ADR 0055).
--
-- Un connecteur interroge l'API d'un hyperviseur (Proxmox VE d'abord) avec un jeton en lecture
-- seule : il voit les hotes et toutes leurs VM sans rien installer dedans. Le jeton n'est jamais
-- en base : seulement sa reference env:NOM, comme les communautes SNMP.
CREATE TABLE hypervisor_connector
(
    id                   UUID PRIMARY KEY,
    name                 VARCHAR(120) NOT NULL,
    kind                 VARCHAR(16)  NOT NULL CHECK (kind IN ('PROXMOX', 'VSPHERE', 'HYPERV')),
    api_url              VARCHAR(255) NOT NULL,
    -- Identifiant du jeton (ex. supervision@pve!trans-dic1) : public, ce n'est pas le secret.
    token_id             VARCHAR(160) NOT NULL,
    -- Reference du secret du jeton (env:NOM), jamais le secret lui-meme.
    secret_reference     VARCHAR(80)  NOT NULL,
    -- Autorite de certification interne (PEM, donnee publique) ; NULL = autorites du systeme.
    ca_certificate       TEXT,
    interval_seconds     INTEGER      NOT NULL CHECK (interval_seconds BETWEEN 30 AND 3600),
    site_id              UUID         NOT NULL REFERENCES site (id) ON DELETE CASCADE,
    create_icmp_probes   BOOLEAN      NOT NULL DEFAULT TRUE,
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    last_run_at          TIMESTAMPTZ,
    last_success_at      TIMESTAMPTZ,
    last_error           VARCHAR(500),
    consecutive_failures INTEGER      NOT NULL DEFAULT 0,
    created_by           VARCHAR(64),
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_hypervisor_connector_site ON hypervisor_connector (site_id);

COMMENT ON TABLE hypervisor_connector IS
    'API d''hyperviseur interrogee sans agent : hotes, VM et conteneurs (ADR 0055)';

-- Hotes, VM et conteneurs vus par un connecteur, rattaches a leur equipement.
--
-- L'identifiant externe est celui de l'hyperviseur (node/pve-01, qemu/101, lxc/202) : c'est lui
-- qui evite les doublons d'une synchronisation a l'autre. Pour une VM, l'UUID SMBIOS distingue
-- une VM recreee qui reprendrait le meme numero. Une ressource disparue de l'hyperviseur n'est
-- pas supprimee : elle est marquee REMOVED et son equipement garde tout son historique.
CREATE TABLE hypervisor_resource
(
    id                UUID PRIMARY KEY,
    connector_id      UUID         NOT NULL REFERENCES hypervisor_connector (id) ON DELETE CASCADE,
    external_id       VARCHAR(160) NOT NULL,
    kind              VARCHAR(16)  NOT NULL CHECK (kind IN ('HOST', 'VM', 'CONTAINER')),
    name              VARCHAR(120) NOT NULL,
    equipment_id      UUID REFERENCES equipment (id) ON DELETE SET NULL,
    host_external_id  VARCHAR(160),
    smbios_uuid       VARCHAR(64),
    power             VARCHAR(16)  NOT NULL
        CHECK (power IN ('RUNNING', 'STOPPED', 'PAUSED', 'ONLINE', 'OFFLINE', 'UNKNOWN')),
    ever_running      BOOLEAN      NOT NULL DEFAULT FALSE,
    ip_address        VARCHAR(45),
    status            VARCHAR(16)  NOT NULL CHECK (status IN ('PRESENT', 'REMOVED')),
    first_seen_at     TIMESTAMPTZ  NOT NULL,
    last_seen_at      TIMESTAMPTZ  NOT NULL,
    removed_at        TIMESTAMPTZ
);

-- Une seule ressource presente par identifiant externe et par connecteur.
CREATE UNIQUE INDEX ux_hypervisor_resource_present
    ON hypervisor_resource (connector_id, external_id)
    WHERE status = 'PRESENT';

CREATE INDEX ix_hypervisor_resource_equipment ON hypervisor_resource (equipment_id);

COMMENT ON TABLE hypervisor_resource IS
    'Hotes, VM et conteneurs vus par un connecteur d''hyperviseur, jamais supprimes (ADR 0055)';
