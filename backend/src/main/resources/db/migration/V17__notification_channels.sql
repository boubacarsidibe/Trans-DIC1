-- Canaux de notification supplémentaires, modèles de messages modifiables et idempotence des envois.

ALTER TABLE notification_preference DROP CONSTRAINT ck_notification_preference_channel;
ALTER TABLE notification_preference ADD CONSTRAINT ck_notification_preference_channel
    CHECK (channel IN ('EMAIL', 'WEBHOOK', 'TEAMS', 'SLACK'));
-- Référence du secret qui contient l'URL du canal (ex. env:TEAMS_NOC_URL) : jamais l'URL elle-même.
ALTER TABLE notification_preference ADD COLUMN destination VARCHAR(100);

ALTER TABLE notification DROP CONSTRAINT ck_notification_channel;
ALTER TABLE notification ADD CONSTRAINT ck_notification_channel
    CHECK (channel IN ('EMAIL', 'WEBHOOK', 'TEAMS', 'SLACK'));
ALTER TABLE notification ALTER COLUMN destination_address TYPE VARCHAR(254);
-- Modèle de message utilisé pour cette notification.
ALTER TABLE notification ADD COLUMN kind VARCHAR(32) NOT NULL DEFAULT 'ALERT_OPENED';
-- Clé d'idempotence : un même événement n'envoie jamais deux fois le même message au même destinataire.
ALTER TABLE notification ADD COLUMN dedup_key VARCHAR(200);
CREATE UNIQUE INDEX uq_notification_dedup_key ON notification(dedup_key) WHERE dedup_key IS NOT NULL;

CREATE TABLE notification_template (
    kind             VARCHAR(32) PRIMARY KEY,
    subject_template VARCHAR(255)  NOT NULL,
    body_template    VARCHAR(4000) NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_notification_template_kind CHECK (kind IN ('ALERT_OPENED', 'ALERT_RESOLVED', 'ALERT_ESCALATED'))
);

INSERT INTO notification_template(kind, subject_template, body_template) VALUES
('ALERT_OPENED', '[{severite}] Alerte ouverte — {equipement}',
 E'Alerte ouverte\n\nÉquipement : {equipement} (site : {site})\nSévérité : {severite}\nMessage : {message}\nDéclenchée le : {declenchee}\n\nConsulter : {url}/alertes\n\n— Trans-DIC1, supervision EPT\n'),
('ALERT_RESOLVED', '[{severite}] Alerte résolue — {equipement}',
 E'Alerte résolue\n\nÉquipement : {equipement} (site : {site})\nSévérité : {severite}\nMessage : {message}\nDéclenchée le : {declenchee}\nRésolue le : {resolue}\n\nConsulter : {url}/alertes\n\n— Trans-DIC1, supervision EPT\n'),
('ALERT_ESCALATED', '[ESCALADE] Alerte non prise en charge — {equipement}',
 E'Alerte non prise en charge\n\nÉquipement : {equipement} (site : {site})\nSévérité : {severite}\nMessage : {message}\nDéclenchée le : {declenchee}\n\nPersonne ne l''a prise en charge dans le délai prévu.\nConsulter : {url}/alertes\n\n— Trans-DIC1, supervision EPT\n');
