-- Cible de disponibilité (SLA) propre à chaque site, en pourcentage. 99,5 % par défaut.
ALTER TABLE site ADD COLUMN sla_target NUMERIC(6, 3) NOT NULL DEFAULT 99.500;
ALTER TABLE site ADD CONSTRAINT ck_site_sla_target CHECK (sla_target >= 50 AND sla_target <= 100);
