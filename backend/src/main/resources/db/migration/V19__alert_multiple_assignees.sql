-- Une alerte peut être suivie par plusieurs opérateurs à la fois : l'unicité porte désormais sur le
-- couple (alerte, opérateur) actif, et non plus sur l'alerte seule.
DROP INDEX uq_alert_assignment_one_active;
CREATE UNIQUE INDEX uq_alert_assignment_one_active
    ON alert_assignment(alert_id, user_id) WHERE ended_at IS NULL;
