-- Alerte instable : une alerte qui s'ouvre et se referme sans arrêt ne doit plus notifier.
-- La date dit depuis quand elle est tenue pour instable ; nulle, l'alerte est normale.
ALTER TABLE alert ADD COLUMN flapping_since TIMESTAMP WITH TIME ZONE;
