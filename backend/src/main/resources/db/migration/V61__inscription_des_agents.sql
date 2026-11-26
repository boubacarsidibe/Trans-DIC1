-- Inscription automatique des agents (ADR 0054).
--
-- Jusqu'ici, superviser un serveur demandait trois gestes dans l'interface : creer l'equipement,
-- enregistrer son agent, recopier le jeton sur la machine. Pour des milliers de serveurs et de VM,
-- ce n'est pas tenable. Un jeton d'inscription partage par toute une vague de deploiement permet a
-- l'agent de s'inscrire lui-meme : il recoit en retour son jeton personnel, et le jeton
-- d'inscription n'ouvre rien d'autre.
CREATE TABLE enrollment_token
(
    id             UUID PRIMARY KEY,
    name           VARCHAR(120) NOT NULL,
    site_id        UUID         NOT NULL REFERENCES site (id) ON DELETE CASCADE,
    -- Empreinte SHA-256 du jeton : une fuite de la base ne permet d'inscrire aucune machine.
    token_hash     VARCHAR(128) NOT NULL UNIQUE,
    expires_at     TIMESTAMPTZ  NOT NULL,
    max_uses       INTEGER      NOT NULL CHECK (max_uses > 0),
    use_count      INTEGER      NOT NULL DEFAULT 0 CHECK (use_count >= 0),
    -- Plages CIDR separees par des virgules ; NULL = toute adresse.
    allowed_ranges VARCHAR(1000),
    approval_mode  VARCHAR(16)  NOT NULL CHECK (approval_mode IN ('AUTO', 'APPROVAL')),
    equipment_type VARCHAR(32)  NOT NULL,
    status         VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_by     VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL,
    revoked_by     VARCHAR(64),
    revoked_at     TIMESTAMPTZ,
    last_used_at   TIMESTAMPTZ
);

CREATE INDEX ix_enrollment_token_site ON enrollment_token (site_id);

COMMENT ON TABLE enrollment_token IS
    'Jetons d''inscription partages : un agent s''inscrit seul et recoit son jeton personnel (ADR 0054)';

-- Machines inscrites ou en attente, une ligne par machine et par site.
--
-- L'identifiant de machine est derive de /etc/machine-id (Linux) ou de MachineGuid (Windows) par
-- l'agent lui-meme (HMAC-SHA256 propre a Trans-DIC1) : l'identifiant brut ne quitte jamais la
-- machine. C'est lui qui rend l'inscription idempotente : la meme machine reinstallee retrouve
-- son equipement au lieu d'en creer un second.
CREATE TABLE agent_enrollment
(
    id             UUID PRIMARY KEY,
    site_id        UUID         NOT NULL REFERENCES site (id) ON DELETE CASCADE,
    token_id       UUID REFERENCES enrollment_token (id) ON DELETE SET NULL,
    machine_id     VARCHAR(128) NOT NULL,
    hostname       VARCHAR(120) NOT NULL,
    ip_addresses   VARCHAR(400),
    source_address VARCHAR(45),
    os_name        VARCHAR(64),
    os_version     VARCHAR(120),
    agent_version  VARCHAR(32),
    status         VARCHAR(16)  NOT NULL
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'ENROLLED')),
    equipment_id   UUID REFERENCES equipment (id) ON DELETE SET NULL,
    requested_at   TIMESTAMPTZ  NOT NULL,
    last_seen_at   TIMESTAMPTZ  NOT NULL,
    decided_by     VARCHAR(64),
    decided_at     TIMESTAMPTZ,
    enrolled_at    TIMESTAMPTZ,
    CONSTRAINT uq_agent_enrollment_machine UNIQUE (site_id, machine_id)
);

CREATE INDEX ix_agent_enrollment_status ON agent_enrollment (status);
CREATE INDEX ix_agent_enrollment_equipment ON agent_enrollment (equipment_id);

COMMENT ON TABLE agent_enrollment IS
    'Machines inscrites par jeton partage, ou en attente d''approbation (ADR 0054)';
