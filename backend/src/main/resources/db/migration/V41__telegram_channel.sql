-- Canal Telegram : l'adresse est une référence de secret qui contient l'URL complète de l'API
-- du bot (jeton compris), comme les autres canaux web. Rien de sensible n'entre en base.
ALTER TABLE notification_preference DROP CONSTRAINT ck_notification_preference_channel;
ALTER TABLE notification_preference ADD CONSTRAINT ck_notification_preference_channel
    CHECK (channel IN ('EMAIL', 'WEBHOOK', 'TEAMS', 'SLACK', 'TELEGRAM'));

ALTER TABLE notification DROP CONSTRAINT ck_notification_channel;
ALTER TABLE notification ADD CONSTRAINT ck_notification_channel
    CHECK (channel IN ('EMAIL', 'WEBHOOK', 'TEAMS', 'SLACK', 'TELEGRAM'));
