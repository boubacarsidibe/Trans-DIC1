-- Actions correctives (remédiation automatique, ADR 0050).
--
-- Le serveur ne connaît que des NOMS d'actions. C'est l'agent qui associe chaque nom à une
-- commande, dans sa propre configuration, protégée sur la machine supervisée : une plateforme
-- compromise ou un administrateur indélicat ne peut pas faire exécuter une commande arbitraire,
-- seulement demander une action que l'exploitant du serveur a lui-même autorisée.

-- 1. Politique d'une règle d'alerte : quelle action lancer quand elle s'ouvre, et comment.
--    AUTO : l'action part tout de suite ; APPROVAL : un opérateur doit l'approuver.
CREATE TABLE remediation_policy (
    id UUID PRIMARY KEY,
    rule_id UUID NOT NULL REFERENCES alert_rule(id) ON DELETE CASCADE,
    action_name VARCHAR(64) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    cooldown_minutes INTEGER NOT NULL DEFAULT 30,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by VARCHAR(120),
    CONSTRAINT uq_remediation_policy_rule UNIQUE (rule_id),
    CONSTRAINT ck_remediation_policy_mode CHECK (mode IN ('AUTO', 'APPROVAL')),
    CONSTRAINT ck_remediation_policy_name CHECK (action_name ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT ck_remediation_policy_cooldown CHECK (cooldown_minutes BETWEEN 0 AND 1440)
);

-- 2. Actions annoncées par chaque agent (noms et descriptions seulement, jamais la commande).
CREATE TABLE remediation_agent_action (
    agent_id UUID NOT NULL REFERENCES collection_agent(id) ON DELETE CASCADE,
    action_name VARCHAR(64) NOT NULL,
    description VARCHAR(200),
    seen_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (agent_id, action_name),
    CONSTRAINT ck_remediation_agent_action_name CHECK (action_name ~ '^[a-z0-9][a-z0-9._-]{0,63}$')
);

-- 3. Exécution d'une action : demandée (automatiquement ou par un opérateur), éventuellement
--    approuvée, prise par l'agent, puis terminée avec son code de sortie et un extrait de sortie.
CREATE TABLE remediation_job (
    id UUID PRIMARY KEY,
    alert_id UUID REFERENCES alert(id) ON DELETE CASCADE,
    equipment_id UUID NOT NULL REFERENCES equipment(id) ON DELETE CASCADE,
    agent_id UUID REFERENCES collection_agent(id) ON DELETE SET NULL,
    rule_id UUID REFERENCES alert_rule(id) ON DELETE SET NULL,
    action_name VARCHAR(64) NOT NULL,
    state VARCHAR(20) NOT NULL,
    trigger_kind VARCHAR(10) NOT NULL,
    requested_by VARCHAR(120),
    decided_by VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ,
    picked_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    exit_code INTEGER,
    output VARCHAR(2000),
    duration_ms BIGINT,
    CONSTRAINT ck_remediation_job_state CHECK (state IN
        ('PENDING_APPROVAL', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT ck_remediation_job_trigger CHECK (trigger_kind IN ('AUTO', 'MANUAL')),
    CONSTRAINT ck_remediation_job_name CHECK (action_name ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT ck_remediation_job_finished CHECK
        ((state IN ('SUCCEEDED', 'FAILED')) = (finished_at IS NOT NULL))
);
CREATE INDEX idx_remediation_job_agent_state ON remediation_job(agent_id, state);
CREATE INDEX idx_remediation_job_alert ON remediation_job(alert_id);
CREATE INDEX idx_remediation_job_rule_equipment ON remediation_job(rule_id, equipment_id, created_at);
-- Une même action ne tourne jamais deux fois en même temps sur un équipement.
CREATE UNIQUE INDEX uq_remediation_job_active ON remediation_job(equipment_id, action_name)
    WHERE state IN ('PENDING_APPROVAL', 'QUEUED', 'RUNNING');
