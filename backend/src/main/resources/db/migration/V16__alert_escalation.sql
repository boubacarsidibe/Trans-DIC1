-- Escalade : une alerte ouverte que personne ne prend en charge est signalée aux administrateurs.
ALTER TABLE alert ADD COLUMN escalated_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE alert_event DROP CONSTRAINT ck_alert_event_type;
ALTER TABLE alert_event ADD CONSTRAINT ck_alert_event_type CHECK (type IN ('OPENED', 'TAKEN', 'ASSIGNED', 'UNASSIGNED', 'SEVERITY_CHANGED', 'COMMENTED', 'NOTIFIED', 'RESOLVED', 'ARCHIVED', 'DELETED', 'ESCALATED'));
