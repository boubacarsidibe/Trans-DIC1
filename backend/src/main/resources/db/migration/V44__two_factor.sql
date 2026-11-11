-- Double authentification par code à usage unique (TOTP). Le secret partagé n'est posé qu'après
-- confirmation d'un premier code : un secret préparé puis abandonné n'active rien.
ALTER TABLE app_user ADD COLUMN totp_secret VARCHAR(64);
ALTER TABLE app_user ADD COLUMN totp_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Un compte ne peut pas être marqué « double authentification active » sans secret.
ALTER TABLE app_user ADD CONSTRAINT ck_app_user_totp
    CHECK (NOT totp_enabled OR totp_secret IS NOT NULL);
