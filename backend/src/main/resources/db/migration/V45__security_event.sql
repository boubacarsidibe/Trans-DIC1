-- Journal de sécurité (lot 5) : lignes de journaux envoyées par l'agent et jugées notables.
-- Tout n'est pas conservé : une requête web ordinaire ne laisse qu'un compteur, pas une ligne.
CREATE TABLE security_event (
    id UUID PRIMARY KEY,
    equipment_id UUID NOT NULL REFERENCES equipment(id) ON DELETE CASCADE,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    kind VARCHAR(24) NOT NULL,
    -- Adresse de celui qui frappe à la porte : donnée personnelle, purgée avec la ligne.
    source_address VARCHAR(45),
    -- Compte visé par une tentative d'authentification.
    username VARCHAR(120),
    method VARCHAR(10),
    request VARCHAR(2000),
    status INTEGER,
    -- Familles d'attaque reconnues, séparées par des virgules ; nul si rien n'a été reconnu.
    families VARCHAR(255),
    score INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_security_event_kind
        CHECK (kind IN ('AUTH_FAILURE', 'AUTH_SUCCESS', 'WEB_ATTACK', 'WEB_ERROR')),
    CONSTRAINT ck_security_event_score CHECK (score >= 0)
);

CREATE INDEX idx_security_event_received ON security_event(received_at DESC);
CREATE INDEX idx_security_event_equipment ON security_event(equipment_id, received_at DESC);
-- Le comptage par adresse (force brute, balayage) interroge toujours une fenêtre de temps.
CREATE INDEX idx_security_event_source ON security_event(source_address, kind, occurred_at);

-- Nouveau type d'alerte : attaque repérée dans les journaux.
ALTER TABLE alert DROP CONSTRAINT ck_alert_type;
ALTER TABLE alert ADD CONSTRAINT ck_alert_type
    CHECK (type IN ('THRESHOLD', 'COLLECTION', 'ANOMALY', 'FORECAST', 'EVENT', 'PEER', 'NO_DATA',
                    'SECURITY'));

ALTER TABLE alert DROP CONSTRAINT ck_alert_type_rule_consistency;
ALTER TABLE alert ADD CONSTRAINT ck_alert_type_rule_consistency CHECK (
    (type = 'THRESHOLD' AND rule_id IS NOT NULL AND event_rule_id IS NULL)
    OR (type = 'EVENT' AND rule_id IS NULL AND event_rule_id IS NOT NULL)
    OR (type IN ('COLLECTION', 'ANOMALY', 'FORECAST', 'PEER', 'NO_DATA', 'SECURITY')
        AND rule_id IS NULL AND event_rule_id IS NULL));
