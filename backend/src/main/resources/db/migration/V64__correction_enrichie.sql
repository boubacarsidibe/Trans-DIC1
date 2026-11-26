-- Correction a distance enrichie (ADR 0057). Le principe de l'ADR 0050 ne change pas : le serveur
-- ne transmet qu'un NOM d'action, la commande est ecrite sur la machine.

-- 1. Verification apres l'action et limite de frequence, par politique de regle.
--    verify_minutes : delai au bout duquel l'alerte doit etre resolue, sinon la correction est
--    jugee inefficace (0 = pas de verification). max_auto_runs sur window_hours : au-dela, la
--    politique passe d'office en approbation (une panne qui revient sans cesse est l'affaire d'un
--    humain).
ALTER TABLE remediation_policy
    ADD COLUMN verify_minutes INTEGER NOT NULL DEFAULT 0
        CONSTRAINT ck_remediation_policy_verify CHECK (verify_minutes BETWEEN 0 AND 240),
    ADD COLUMN max_auto_runs INTEGER NOT NULL DEFAULT 3
        CONSTRAINT ck_remediation_policy_max_runs CHECK (max_auto_runs BETWEEN 1 AND 100),
    ADD COLUMN window_hours INTEGER NOT NULL DEFAULT 24
        CONSTRAINT ck_remediation_policy_window CHECK (window_hours BETWEEN 1 AND 168);

-- 2. Resultat de la verification d'une execution, et raison d'une approbation imposee.
ALTER TABLE remediation_job
    ADD COLUMN verify_due_at TIMESTAMPTZ,
    ADD COLUMN verification VARCHAR(16)
        CONSTRAINT ck_remediation_job_verification CHECK (verification IN ('EFFECTIVE', 'INEFFECTIVE')),
    ADD COLUMN verified_at TIMESTAMPTZ,
    ADD COLUMN approval_reason VARCHAR(200);

CREATE INDEX ix_remediation_job_verify ON remediation_job (verify_due_at)
    WHERE verify_due_at IS NOT NULL AND verification IS NULL;

-- 3. Actions sur les VM par l'hyperviseur : un second jeton, distinct, au droit d'ecriture limite
--    (VM.PowerMgmt sous Proxmox), par sa seule reference env:NOM. Desactive par defaut.
ALTER TABLE hypervisor_connector
    ADD COLUMN power_token_id VARCHAR(160),
    ADD COLUMN power_secret_reference VARCHAR(80),
    ADD COLUMN vm_actions_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Une demande de redemarrage de VM : toujours approuvee par un administrateur, tracee.
CREATE TABLE hypervisor_action
(
    id           UUID PRIMARY KEY,
    resource_id  UUID         NOT NULL REFERENCES hypervisor_resource (id) ON DELETE CASCADE,
    action       VARCHAR(16)  NOT NULL CHECK (action IN ('RESET')),
    state        VARCHAR(20)  NOT NULL
        CHECK (state IN ('PENDING_APPROVAL', 'SUCCEEDED', 'FAILED', 'REJECTED', 'EXPIRED')),
    reason       VARCHAR(300) NOT NULL,
    requested_by VARCHAR(64),
    decided_by   VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL,
    decided_at   TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ  NOT NULL,
    result       VARCHAR(500)
);

-- Une seule demande en attente par VM.
CREATE UNIQUE INDEX ux_hypervisor_action_pending
    ON hypervisor_action (resource_id)
    WHERE state = 'PENDING_APPROVAL';

COMMENT ON TABLE hypervisor_action IS
    'Redemarrage force d''une VM bloquee par l''API de l''hyperviseur, sur approbation (ADR 0057)';
