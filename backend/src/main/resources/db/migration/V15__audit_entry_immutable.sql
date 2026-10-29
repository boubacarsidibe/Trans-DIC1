-- Le journal d'audit est en ajout seul : aucune modification possible, sauf le détachement de
-- l'auteur quand son compte est supprimé (ON DELETE SET NULL, le nom reste dans username).
CREATE OR REPLACE FUNCTION audit_entry_guard() RETURNS trigger AS $$
BEGIN
    IF NEW.author_id IS NULL AND OLD.author_id IS NOT NULL
       AND (NEW.id, NEW.username, NEW.action, NEW.target_type, NEW.target_id, NEW.correlation_id,
            NEW.details_sanitized, NEW.performed_at)
           IS NOT DISTINCT FROM
           (OLD.id, OLD.username, OLD.action, OLD.target_type, OLD.target_id, OLD.correlation_id,
            OLD.details_sanitized, OLD.performed_at) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Le journal d audit est en lecture seule';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_entry_no_update BEFORE UPDATE ON audit_entry
    FOR EACH ROW EXECUTE FUNCTION audit_entry_guard();
