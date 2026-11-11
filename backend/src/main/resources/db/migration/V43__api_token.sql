-- Jetons d'API : permettent à un outil tiers d'appeler l'API sans compte humain interactif.
-- Seule l'empreinte du jeton est stockée ; une fuite de la base ne donne aucun accès. Le jeton
-- porte les droits et le périmètre de son propriétaire, relus à chaque requête.
CREATE TABLE api_token (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    -- Début du jeton, affiché pour reconnaître une ligne sans révéler le secret.
    prefix VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,
    last_used_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_api_token_user_name UNIQUE (user_id, name)
);

CREATE INDEX idx_api_token_user ON api_token(user_id);
