-- Proxys de collecte par site, avec tampon hors ligne (ADR 0047).
--
-- Jusqu'ici, chaque equipement portait son propre agent et son propre jeton. Un site derriere une
-- liaison qui tombe perdait donc ses mesures, sans rattrapage : rien dans le serveur ne savait
-- recevoir un lot vieux de six heures. Un proxy par site relaie les mesures de tous les
-- equipements du site et garde ce qu'il n'a pas pu envoyer.
CREATE TABLE collection_proxy
(
    id                     UUID PRIMARY KEY,
    site_id                UUID        NOT NULL REFERENCES site (id) ON DELETE CASCADE,
    identifier             VARCHAR(64) NOT NULL UNIQUE,
    status                 VARCHAR(32) NOT NULL,
    version                VARCHAR(32),
    ip_address             VARCHAR(45),
    registered_at          TIMESTAMPTZ NOT NULL,
    last_communication_at  TIMESTAMPTZ,
    revoked_at             TIMESTAMPTZ,
    -- Empreinte SHA-256 du jeton : une fuite de la base ne donne aucun acces, comme pour les agents.
    token_hash             VARCHAR(128)
);

-- Un seul proxy actif par site : changer de jeton, c'est enregistrer a nouveau.
CREATE UNIQUE INDEX ux_collection_proxy_site_active
    ON collection_proxy (site_id)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_collection_proxy_status ON collection_proxy (status);

COMMENT ON TABLE collection_proxy IS
    'Relais de collecte d''un site : un jeton pour tous les equipements du site (ADR 0047)';

-- Lots deja recus, pour qu'un rejeu ne compte pas deux fois.
--
-- Un proxy qui vide son tampon et perd l'accuse de reception renvoie le meme lot : sans cette
-- table, les mesures seraient stockees en double et les moyennes horaires fausses. Le proxy
-- numerote son lot, le serveur se souvient de ce qu'il a repondu.
CREATE TABLE proxy_batch
(
    id            UUID PRIMARY KEY,
    proxy_id      UUID        NOT NULL REFERENCES collection_proxy (id) ON DELETE CASCADE,
    lot_id        UUID        NOT NULL,
    received_at   TIMESTAMPTZ NOT NULL,
    stored        INTEGER     NOT NULL,
    evaluated     INTEGER     NOT NULL,
    alerts_opened INTEGER     NOT NULL,
    alerts_closed INTEGER     NOT NULL,
    CONSTRAINT uq_proxy_batch_lot UNIQUE (proxy_id, lot_id)
);

CREATE INDEX ix_proxy_batch_received ON proxy_batch (received_at);

COMMENT ON TABLE proxy_batch IS
    'Lots deja acceptes d''un proxy : un rejeu renvoie le premier bilan sans rien restocker';
