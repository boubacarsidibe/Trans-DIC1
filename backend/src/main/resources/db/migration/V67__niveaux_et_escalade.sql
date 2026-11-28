-- Niveaux de support et escalade traçable (ADR 0059).

-- 1. Niveau de support facultatif d'un operateur NOC (1 = N1, 2 = N2, 3 = N3), fixe par
--    l'administrateur. Vide : l'operateur n'a pas de niveau, et un niveau sans personne est saute.
ALTER TABLE app_user
    ADD COLUMN support_level INTEGER
        CONSTRAINT ck_app_user_support_level CHECK (support_level BETWEEN 1 AND 3);

-- 2. Niveau courant d'une alerte : celui de la personne d'astreinte prevenue, sinon de l'operateur
--    qui la prend ; il change a chaque escalade ou renvoi. 4 : escaladee aux administrateurs, faute
--    de niveau superieur occupe (aucun operateur ne la prend plus).
ALTER TABLE alert
    ADD COLUMN current_level INTEGER
        CONSTRAINT ck_alert_current_level CHECK (current_level BETWEEN 1 AND 4);

-- 3. Journal d'intervention : notes, escalades, renvois et entrees automatiques (diagnostic
--    consulte, cause confirmee, action corrective et son resultat) dans la chronologie de l'alerte.
ALTER TABLE alert_event DROP CONSTRAINT ck_alert_event_type;
ALTER TABLE alert_event ADD CONSTRAINT ck_alert_event_type CHECK (type IN (
    'OPENED', 'TAKEN', 'ASSIGNED', 'UNASSIGNED', 'SEVERITY_CHANGED', 'COMMENTED', 'NOTIFIED',
    'RESOLVED', 'ARCHIVED', 'DELETED', 'ESCALATED', 'CORRELATED',
    'NOTE', 'LEVEL_ESCALATED', 'SENT_BACK', 'DIAGNOSTIC_VIEWED', 'CAUSE_CONFIRMED', 'REMEDIATION'));

ALTER TABLE alert_event
    ADD COLUMN from_level INTEGER,
    ADD COLUMN to_level INTEGER,
    -- Cases cochees de l'escalade, par leur code (SERVICE_RESTARTED, ...), separees par des virgules.
    ADD COLUMN checklist VARCHAR(300),
    -- Destinataires prevenus (noms de compte), renseignes par l'astreinte au moment de l'envoi.
    ADD COLUMN recipients VARCHAR(1000);

CREATE INDEX ix_alert_event_level ON alert_event (type, occurred_at)
    WHERE type IN ('LEVEL_ESCALATED', 'SENT_BACK');
