-- Prédiction du risque de panne à 24 heures (ADR 0052).
--
-- Un seul modèle, réentraîné chaque nuit sur l'historique de la plateforme : ses poids, la façon
-- dont il a été obtenu et ses scores d'évaluation sont gardés pour que l'écran puisse les montrer.
-- Le modèle est une régression logistique : chaque poids se lit (« un disque plein multiplie le
-- risque par 3 »), ce qu'aucun réseau de neurones ne permettrait.
CREATE TABLE risk_model (
    id SMALLINT PRIMARY KEY DEFAULT 1,
    trained_at TIMESTAMPTZ NOT NULL,
    samples INTEGER NOT NULL,
    positives INTEGER NOT NULL,
    mode VARCHAR(16) NOT NULL,
    auc NUMERIC(6, 4),
    brier NUMERIC(8, 6),
    base_rate NUMERIC(8, 6) NOT NULL,
    evaluation_samples INTEGER NOT NULL,
    weights_json VARCHAR(4000) NOT NULL,
    CONSTRAINT ck_risk_model_single CHECK (id = 1),
    CONSTRAINT ck_risk_model_mode CHECK (mode IN ('POIDS_INITIAUX', 'MELANGE', 'APPRIS')),
    CONSTRAINT ck_risk_model_counts CHECK (samples >= 0 AND positives >= 0 AND positives <= samples)
);
