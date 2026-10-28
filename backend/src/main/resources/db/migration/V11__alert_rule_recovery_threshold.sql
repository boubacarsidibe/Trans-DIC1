-- Hystérésis : seuil de retour à la normale distinct du seuil d'ouverture. Nul = même seuil.
-- Pour un opérateur « supérieur », le seuil de retour est inférieur ou égal au seuil d'ouverture ;
-- pour un opérateur « inférieur », il est supérieur ou égal ; pour « égal » il n'a pas de sens.
ALTER TABLE alert_rule ADD COLUMN recovery_threshold NUMERIC(12, 4);
ALTER TABLE alert_rule ADD CONSTRAINT ck_alert_rule_recovery_threshold CHECK (
    recovery_threshold IS NULL
    OR (operator IN ('GREATER_THAN', 'GREATER_OR_EQUAL') AND recovery_threshold <= threshold)
    OR (operator IN ('LESS_THAN', 'LESS_OR_EQUAL') AND recovery_threshold >= threshold)
);
