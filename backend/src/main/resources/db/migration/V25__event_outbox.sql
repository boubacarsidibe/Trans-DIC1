-- Événements internes relayés entre instances (ARCH-10, ADR 0016) : écrits dans la transaction qui
-- les produit, publiés ensuite par NOTIFY. Une ligne non publiée est republiée : rien ne se perd.
CREATE TABLE event_outbox (
    id            BIGSERIAL PRIMARY KEY,
    event_id      UUID         NOT NULL,
    type          VARCHAR(40)  NOT NULL,
    version       SMALLINT     NOT NULL DEFAULT 1,
    payload       VARCHAR(1000) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at  TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_event_outbox_event_id UNIQUE (event_id)
);
CREATE INDEX idx_event_outbox_pending ON event_outbox(id) WHERE published_at IS NULL;
