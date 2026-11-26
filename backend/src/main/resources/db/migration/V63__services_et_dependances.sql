-- Services decouverts dans les machines, et chaine de dependances pour la cause racine (ADR 0056).
--
-- 1. Un service systeme suivi a la demande d'un administrateur (nginx, postgresql, Spooler) devient
--    un service supervise de type AGENT_SERVICE : l'agent en remonte l'etat dans service.up[nom]
--    sans que sa configuration soit modifiee sur la machine.
ALTER TABLE monitored_service DROP CONSTRAINT ck_monitored_service_type;
ALTER TABLE monitored_service ADD CONSTRAINT ck_monitored_service_type
    CHECK (type IN ('TCP_PORT', 'HTTP', 'HTTPS', 'ICMP', 'DNS', 'TLS_CERT', 'OTHER', 'AGENT_SERVICE'));

-- 2. L'instantane de l'agent porte maintenant la liste des services actifs et des ports en ecoute :
--    la place prevue pour le seul top des processus ne suffit plus.
ALTER TABLE agent_snapshot ALTER COLUMN payload TYPE VARCHAR(100000);

-- 3. Un service depend d'un autre service (l'ENT depend de PostgreSQL). Avec « un service tourne
--    sur un equipement » (monitored_service.equipment_id) et « un equipement depend de son amont »
--    (equipment_link), c'est la chaine que parcourt la recherche de cause racine. Les cycles sont
--    refuses a l'enregistrement : une dependance circulaire ne designerait aucune cause.
CREATE TABLE service_dependency
(
    id            UUID PRIMARY KEY,
    service_id    UUID        NOT NULL REFERENCES monitored_service (id) ON DELETE CASCADE,
    depends_on_id UUID        NOT NULL REFERENCES monitored_service (id) ON DELETE CASCADE,
    created_by    VARCHAR(64),
    created_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_service_dependency UNIQUE (service_id, depends_on_id),
    CONSTRAINT ck_service_dependency_not_self CHECK (service_id <> depends_on_id)
);

CREATE INDEX ix_service_dependency_depends_on ON service_dependency (depends_on_id);

COMMENT ON TABLE service_dependency IS
    'Un service depend d''un autre service : chaine parcourue par la cause racine (ADR 0056)';
