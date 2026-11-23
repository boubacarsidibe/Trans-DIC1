-- Regroupement des alertes en incidents (M10, ADR 0039).
--
-- Un incident rassemble les alertes qui decrivent un meme fait : une panne en amont qui fait
-- tomber cinq machines, une inondation qui touche tout un site. L'interet est double : une seule
-- notification par incident au lieu d'une par alerte, et un ecran ou l'on voit le fait entier
-- plutot que ses morceaux.
--
-- notified_severity retient la plus haute severite deja notifiee pour l'incident. Un booleen ne
-- suffirait pas : un incident notifie en avertissement qui devient critique doit reveiller
-- quelqu'un une seconde fois.
CREATE TABLE incident (
    id UUID PRIMARY KEY,
    site_id UUID,
    title VARCHAR(300) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    state VARCHAR(32) NOT NULL,
    opened_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_alert_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    notified_severity VARCHAR(32),
    CONSTRAINT fk_incident_site_id FOREIGN KEY (site_id) REFERENCES site(id) ON DELETE SET NULL,
    CONSTRAINT ck_incident_state CHECK (state IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_incident_severity CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_incident_notified_severity CHECK (notified_severity IS NULL OR notified_severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_incident_resolved_at CHECK ((state = 'RESOLVED') = (resolved_at IS NOT NULL)),
    CONSTRAINT ck_incident_last_alert_at CHECK (last_alert_at >= opened_at)
);
CREATE INDEX idx_incident_state_opened_at ON incident(state, opened_at DESC);
CREATE INDEX idx_incident_site_id ON incident(site_id);

-- Une alerte appartient a un incident au plus : alert_id est la cle primaire. La raison est
-- conservee en clair, comme pour correlation_reason sur alert : un regroupement qu'on ne peut pas
-- expliquer n'est pas verifiable par l'operateur.
CREATE TABLE incident_alert (
    alert_id UUID PRIMARY KEY,
    incident_id UUID NOT NULL,
    joined_at TIMESTAMP WITH TIME ZONE NOT NULL,
    reason VARCHAR(300) NOT NULL,
    -- Vrai pour l'alerte qui notifie au nom de l'incident. Decide une fois, au rattachement : la
    -- file des notifications et l'astreinte doivent pouvoir le lire chacune de son cote.
    announces BOOLEAN NOT NULL,
    CONSTRAINT fk_incident_alert_incident_id FOREIGN KEY (incident_id) REFERENCES incident(id) ON DELETE CASCADE,
    CONSTRAINT fk_incident_alert_alert_id FOREIGN KEY (alert_id) REFERENCES alert(id) ON DELETE CASCADE
);
CREATE INDEX idx_incident_alert_incident_id ON incident_alert(incident_id);
