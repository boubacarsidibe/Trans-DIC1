-- Prise en charge depuis Telegram (ADR 0051).
--
-- Un clic sur un bouton Telegram doit agir au nom d'une personne précise, avec ses droits et son
-- périmètre. Le lien entre un compte de la plateforme et un compte Telegram est donc établi par la
-- personne elle-même : elle génère un code à usage unique dans l'application, puis l'envoie au bot
-- (/lier CODE). Le code n'est gardé que sous forme d'empreinte, et il expire en dix minutes.

CREATE TABLE chatops_telegram_link (
    user_id UUID PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    telegram_user_id BIGINT NOT NULL,
    chat_id BIGINT NOT NULL,
    telegram_username VARCHAR(64),
    linked_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_chatops_telegram_user UNIQUE (telegram_user_id)
);

CREATE TABLE chatops_link_code (
    code_hash VARCHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_chatops_link_code_user ON chatops_link_code(user_id);
