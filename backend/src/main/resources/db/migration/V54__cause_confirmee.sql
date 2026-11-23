-- Cause confirmee par l'operateur (M12, ADR 0045).
--
-- Une ligne par cause *proposee* au moment de la confirmation, et non une seule ligne pour la cause
-- retenue : sans les propositions ecartees, on ne saurait pas distinguer « cette source a raison
-- neuf fois sur dix » de « cette source est proposee neuf fois sur dix ». Ce sont les refus qui
-- portent l'information.
--
-- chosen vaut vrai pour celle que l'operateur a designee. Une confirmation ou il repond « aucune de
-- ces causes » n'a que des lignes a faux, et c'est une information precieuse : elle dit que le
-- diagnostic s'est trompe sur toute la ligne.
CREATE TABLE cause_feedback (
    id UUID PRIMARY KEY,
    alert_id UUID NOT NULL REFERENCES alert(id) ON DELETE CASCADE,
    source VARCHAR(32) NOT NULL,
    -- Force de la preuve et ecart a l'ouverture, conserves pour pouvoir un jour apprendre autre
    -- chose que le poids par source sans avoir perdu l'historique.
    strength DOUBLE PRECISION NOT NULL,
    minutes_from_opening DOUBLE PRECISION,
    chosen BOOLEAN NOT NULL,
    confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    confirmed_by VARCHAR(120) NOT NULL,
    CONSTRAINT ck_cause_feedback_source CHECK (source IN (
        'AMONT_EN_ALERTE', 'EVENEMENT_RECENT', 'CHANGEMENT_RECENT', 'SILENCE_COLLECTE',
        'TENDANCE_SATURATION', 'ANOMALIE')),
    CONSTRAINT ck_cause_feedback_strength CHECK (strength >= 0 AND strength <= 1),
    -- Une alerte n'est confirmee qu'une fois par source : reconfirmer remplace, il n'empile pas.
    CONSTRAINT uq_cause_feedback UNIQUE (alert_id, source)
);

CREATE INDEX idx_cause_feedback_source ON cause_feedback(source, chosen);
CREATE INDEX idx_cause_feedback_confirmed_at ON cause_feedback(confirmed_at);
