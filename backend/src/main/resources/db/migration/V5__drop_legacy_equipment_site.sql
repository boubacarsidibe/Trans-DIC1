-- V3 a introduit equipment.site_id sans retirer l'ancienne colonne texte « site »
-- (NOT NULL). L'entité ne la mappe plus : tout INSERT via l'API échouait.
ALTER TABLE equipment DROP COLUMN site;
