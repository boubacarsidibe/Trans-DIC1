-- Suivi automatique des services (ADR 0060). L'agent 2.3 remonte tous les services avec leur mode
-- de demarrage ; la plateforme suit d'office ceux qui demarrent automatiquement.

-- 1. Mode de demarrage vu au dernier instantane (AUTO, AUTO_DELAYED, MANUAL, DISABLED, UNKNOWN) et
--    origine du suivi : d'office (auto_followed) ou par un administrateur. Le mode sert au message
--    de l'alerte : un service a demarrage automatique arrete « devrait tourner ».
ALTER TABLE monitored_service
    ADD COLUMN start_mode VARCHAR(16)
        CONSTRAINT ck_monitored_service_start_mode
            CHECK (start_mode IN ('AUTO', 'AUTO_DELAYED', 'MANUAL', 'DISABLED', 'UNKNOWN')),
    ADD COLUMN auto_followed BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. Services qu'un administrateur a cesse de suivre : « ignores », ils ne sont plus jamais suivis
--    d'office. Suivre a nouveau le service retire la ligne.
CREATE TABLE ignored_agent_service (
    id UUID PRIMARY KEY,
    equipment_id UUID NOT NULL REFERENCES equipment(id) ON DELETE CASCADE,
    name VARCHAR(64) NOT NULL,
    ignored_by VARCHAR(64),
    ignored_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_ignored_agent_service UNIQUE (equipment_id, name)
);
