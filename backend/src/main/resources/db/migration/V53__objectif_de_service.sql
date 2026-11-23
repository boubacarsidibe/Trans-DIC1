-- Objectif de disponibilite d'un service et budget d'erreur (M8, ADR 0044).
--
-- Nul par defaut : sans objectif, il n'y a pas de budget, et la plateforme ne reproche rien. C'est
-- une decision d'exploitation, pas une valeur qu'un outil peut deviner.
ALTER TABLE monitored_service
    ADD COLUMN sla_target NUMERIC(6, 5);

-- Entre 50 % et 100 % : en dessous, « objectif » ne veut plus rien dire ; a 100 %, le budget est
-- nul et le moindre echec le manque, ce qui est un choix legitime mais doit etre explicite.
ALTER TABLE monitored_service
    ADD CONSTRAINT ck_monitored_service_sla_target
        CHECK (sla_target IS NULL OR (sla_target >= 0.5 AND sla_target <= 1));
