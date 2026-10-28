-- ADR-0005 : rotation des jetons de renouvellement avec détection de réutilisation.
-- Les sessions d'une même connexion partagent un family_id ; remplacer un jeton enregistre le suivant.
ALTER TABLE user_session ADD COLUMN family_id UUID;
UPDATE user_session SET family_id = id;
ALTER TABLE user_session ALTER COLUMN family_id SET NOT NULL;
ALTER TABLE user_session ADD COLUMN replaced_by_id UUID;
CREATE INDEX idx_user_session_family_id ON user_session(family_id);
