-- Taux d'anomalie par équipement (M3) : part des indicateurs hors de leur normale au dernier
-- passage du détecteur. Écrit par AnomalyDetectionService, lu par le score de santé.
-- Une seule ligne par équipement, remplacée à chaque passage : pas d'historique à purger.
CREATE TABLE equipment_anomaly_rate (
    equipment_id UUID PRIMARY KEY REFERENCES equipment(id) ON DELETE CASCADE,
    judged INTEGER NOT NULL,
    anomalous INTEGER NOT NULL,
    computed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_equipment_anomaly_rate_counts CHECK (judged >= 0 AND anomalous BETWEEN 0 AND judged)
);
