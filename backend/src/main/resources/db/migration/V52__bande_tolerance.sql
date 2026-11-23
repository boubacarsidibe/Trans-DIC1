-- Bande de tolerance par lissage exponentiel (M6, ADR 0043).
--
-- Un etat par indicateur et par equipement, tenu entre deux passages : le niveau lisse, l'ecart
-- absolu moyen lisse, et le nombre de mesures vues. Quatre nombres, ce qui permet d'en garder pour
-- des milliers de ports la ou la normale par creneau demanderait des semaines d'historique chacune.
--
-- streak compte les mesures consecutives du meme cote de la bande : positif dehors, negatif dedans.
-- Une seule mesure hors bande n'ouvre rien, et une seule mesure revenue ne referme rien.
CREATE TABLE metric_band (
    equipment_id UUID NOT NULL REFERENCES equipment(id) ON DELETE CASCADE,
    metric_key VARCHAR(64) NOT NULL,
    level DOUBLE PRECISION NOT NULL,
    deviation DOUBLE PRECISION NOT NULL,
    samples INTEGER NOT NULL,
    streak INTEGER NOT NULL DEFAULT 0,
    -- Instant de la derniere mesure integree : le passage suivant repart de la.
    last_sample_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (equipment_id, metric_key),
    CONSTRAINT ck_metric_band_samples CHECK (samples > 0),
    CONSTRAINT ck_metric_band_deviation CHECK (deviation >= 0)
);

CREATE INDEX idx_metric_band_updated_at ON metric_band(updated_at);
