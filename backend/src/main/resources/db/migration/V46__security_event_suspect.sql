-- Modèle appris (S4) : une requête peut désormais être retenue comme « forme inhabituelle »,
-- sans qu'aucune signature ne la reconnaisse.
ALTER TABLE security_event DROP CONSTRAINT ck_security_event_kind;
ALTER TABLE security_event ADD CONSTRAINT ck_security_event_kind
    CHECK (kind IN ('AUTH_FAILURE', 'AUTH_SUCCESS', 'WEB_ATTACK', 'WEB_ERROR', 'WEB_SUSPECT'));
