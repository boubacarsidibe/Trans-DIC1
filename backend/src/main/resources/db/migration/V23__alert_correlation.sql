-- Regroupement des alertes : une alerte de collecte peut être rattachée à l'alerte racine qui
-- l'explique (amont en panne, ou même site dans la même fenêtre de temps), avec la raison.
ALTER TABLE alert ADD COLUMN parent_alert_id UUID;
ALTER TABLE alert ADD COLUMN correlation_reason VARCHAR(255);
ALTER TABLE alert ADD CONSTRAINT fk_alert_parent FOREIGN KEY (parent_alert_id) REFERENCES alert(id) ON DELETE SET NULL;
ALTER TABLE alert ADD CONSTRAINT ck_alert_not_own_parent CHECK (parent_alert_id IS NULL OR parent_alert_id <> id);
CREATE INDEX idx_alert_parent ON alert(parent_alert_id);

ALTER TABLE alert_event DROP CONSTRAINT ck_alert_event_type;
ALTER TABLE alert_event ADD CONSTRAINT ck_alert_event_type CHECK (type IN ('OPENED', 'TAKEN', 'ASSIGNED', 'UNASSIGNED', 'SEVERITY_CHANGED', 'COMMENTED', 'NOTIFIED', 'RESOLVED', 'ARCHIVED', 'DELETED', 'ESCALATED', 'CORRELATED'));
