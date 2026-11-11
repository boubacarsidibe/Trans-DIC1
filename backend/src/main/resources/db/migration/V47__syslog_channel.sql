-- Canal syslog (S8, ADR 0031) : les alertes de la plateforme partent vers le collecteur d'un SIEM.
-- L'adresse du collecteur est une référence de secret, comme pour les autres canaux non courriel ;
-- sa valeur s'écrit udp://hote:514 ou tcp://hote:514.
ALTER TABLE notification_preference DROP CONSTRAINT ck_notification_preference_channel;
ALTER TABLE notification_preference ADD CONSTRAINT ck_notification_preference_channel
    CHECK (channel IN ('EMAIL', 'WEBHOOK', 'TEAMS', 'SLACK', 'TELEGRAM', 'SYSLOG'));

ALTER TABLE notification DROP CONSTRAINT ck_notification_channel;
ALTER TABLE notification ADD CONSTRAINT ck_notification_channel
    CHECK (channel IN ('EMAIL', 'WEBHOOK', 'TEAMS', 'SLACK', 'TELEGRAM', 'SYSLOG'));

-- Sens retour : les alertes du SIEM arrivent par le récepteur syslog déjà en place (ADR 0021).
-- Règle livrée inactive, comme les autres : l'exploitation l'active et l'affine quand le SIEM
-- émet vraiment. Le gestionnaire Wazuh doit être inventorié, sinon ses messages sont rejetés.
INSERT INTO event_rule (id, name, source, min_severity, oid_prefix, message_regex, resolve_oid,
                        resolve_regex, alert_severity, dedup_window_seconds, active)
VALUES
    (gen_random_uuid(), 'Alerte d''un SIEM (Wazuh)', 'SYSLOG', NULL, NULL,
     '(?i)\b(wazuh|ossec)\b', NULL, NULL, 'WARNING', 300, FALSE)
ON CONFLICT (name) DO NOTHING;
