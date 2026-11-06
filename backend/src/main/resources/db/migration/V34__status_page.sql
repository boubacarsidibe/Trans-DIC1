-- Pages de statut (F2.3, ADR 0026) : pages internes ou publiques, composants rattachés à une source
-- supervisée (sonde, équipement ou site, jamais un nom d'hôte libre), incidents et leurs mises à jour.
-- Texte brut seulement (ni HTML ni markdown) ; toutes les dates en UTC (TIMESTAMPTZ).

-- 1. Page : identifiant d'URL (slug) unique, visibilité interne ou publique.
CREATE TABLE status_page (
    id UUID PRIMARY KEY,
    slug VARCHAR(60) NOT NULL,
    title VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    visibility VARCHAR(16) NOT NULL DEFAULT 'INTERNAL',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_status_page_slug UNIQUE (slug),
    CONSTRAINT ck_status_page_slug CHECK (slug ~ '^[a-z0-9-]{3,60}$'),
    CONSTRAINT ck_status_page_visibility CHECK (visibility IN ('INTERNAL', 'PUBLIC'))
);

-- 2. Composant : ce que voient les usagers (« Messagerie »), rattaché à une source supervisée.
--    La source n'est pas une clé étrangère (trois tables possibles) : une source supprimée laisse
--    un composant à l'état inconnu, signalé à l'administrateur.
--    outage_since / auto_incident_opened servent à l'incident automatique (désactivé par défaut).
CREATE TABLE status_component (
    id UUID PRIMARY KEY,
    page_id UUID NOT NULL REFERENCES status_page(id) ON DELETE CASCADE,
    display_name VARCHAR(80) NOT NULL,
    group_name VARCHAR(60),
    position INTEGER NOT NULL DEFAULT 0,
    source_type VARCHAR(16) NOT NULL,
    source_id UUID NOT NULL,
    outage_since TIMESTAMPTZ,
    auto_incident_opened BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_status_component_source CHECK (source_type IN ('PROBE', 'EQUIPMENT', 'SITE')),
    CONSTRAINT ck_status_component_position CHECK (position >= 0)
);
CREATE INDEX idx_status_component_page ON status_component(page_id, position);

-- 3. Incident : saisi par un administrateur, ou ouvert automatiquement (auto_component_id).
CREATE TABLE status_incident (
    id UUID PRIMARY KEY,
    page_id UUID NOT NULL REFERENCES status_page(id) ON DELETE CASCADE,
    title VARCHAR(160) NOT NULL,
    status VARCHAR(16) NOT NULL,
    impact VARCHAR(8) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    author VARCHAR(255),
    auto_component_id UUID REFERENCES status_component(id) ON DELETE SET NULL,
    CONSTRAINT ck_status_incident_status
        CHECK (status IN ('INVESTIGATING', 'IDENTIFIED', 'MONITORING', 'RESOLVED')),
    CONSTRAINT ck_status_incident_impact CHECK (impact IN ('NONE', 'MINOR', 'MAJOR')),
    CONSTRAINT ck_status_incident_resolved
        CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL))
);
CREATE INDEX idx_status_incident_page ON status_incident(page_id, created_at DESC);
-- Idempotence de l'incident automatique : un seul incident automatique ouvert par composant.
CREATE UNIQUE INDEX uq_status_incident_auto_open ON status_incident(auto_component_id)
    WHERE auto_component_id IS NOT NULL AND status <> 'RESOLVED';

CREATE TABLE status_incident_component (
    incident_id UUID NOT NULL REFERENCES status_incident(id) ON DELETE CASCADE,
    component_id UUID NOT NULL REFERENCES status_component(id) ON DELETE CASCADE,
    PRIMARY KEY (incident_id, component_id)
);
CREATE INDEX idx_status_incident_component_component ON status_incident_component(component_id);

-- 4. Mise à jour d'un incident : statut et message, dans l'ordre du temps.
CREATE TABLE status_incident_update (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES status_incident(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    author VARCHAR(255),
    CONSTRAINT ck_status_incident_update_status
        CHECK (status IN ('INVESTIGATING', 'IDENTIFIED', 'MONITORING', 'RESOLVED'))
);
CREATE INDEX idx_status_incident_update_incident ON status_incident_update(incident_id, created_at);
