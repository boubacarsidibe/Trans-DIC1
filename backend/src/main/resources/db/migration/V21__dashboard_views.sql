-- Vues de tableau de bord personnelles : chaque utilisateur compose et ordonne ses widgets.
CREATE TABLE dashboard_view (
    id          UUID PRIMARY KEY,
    owner_id    UUID         NOT NULL,
    name        VARCHAR(80)  NOT NULL,
    widgets     VARCHAR(500) NOT NULL,
    position    INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_dashboard_view_owner FOREIGN KEY (owner_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT uq_dashboard_view_owner_name UNIQUE (owner_id, name)
);
CREATE INDEX idx_dashboard_view_owner ON dashboard_view(owner_id, position);
