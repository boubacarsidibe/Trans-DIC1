-- Astreinte (F2.2, ADR 0025) : plannings, gardes, rotations et étapes d'escalade vers la personne
-- d'astreinte. Toutes les dates sont stockées en UTC (TIMESTAMPTZ) ; le fuseau du planning ne sert
-- qu'à placer l'heure de relève d'une rotation.

-- 1. Planning : tous les sites (all_sites) ou une liste de sites (oncall_schedule_site).
CREATE TABLE oncall_schedule (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    timezone VARCHAR(64) NOT NULL DEFAULT 'Africa/Dakar',
    all_sites BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
-- Nom unique sans tenir compte de la casse.
CREATE UNIQUE INDEX uq_oncall_schedule_name ON oncall_schedule (lower(name));

CREATE TABLE oncall_schedule_site (
    schedule_id UUID NOT NULL REFERENCES oncall_schedule(id) ON DELETE CASCADE,
    site_id UUID NOT NULL REFERENCES site(id) ON DELETE CASCADE,
    PRIMARY KEY (schedule_id, site_id)
);
CREATE INDEX idx_oncall_schedule_site_site ON oncall_schedule_site(site_id);

-- 2. Rotation : une par planning et par niveau ; liste ordonnée de personnes, relève tous les
--    shift_length_days jours à handover_time (heure locale du fuseau du planning).
CREATE TABLE oncall_rotation (
    id UUID PRIMARY KEY,
    schedule_id UUID NOT NULL REFERENCES oncall_schedule(id) ON DELETE CASCADE,
    level VARCHAR(16) NOT NULL,
    start_date DATE NOT NULL,
    shift_length_days INTEGER NOT NULL,
    handover_time TIME NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_oncall_rotation_level CHECK (level IN ('PRIMARY', 'SECONDARY')),
    CONSTRAINT ck_oncall_rotation_length CHECK (shift_length_days BETWEEN 1 AND 31),
    CONSTRAINT uq_oncall_rotation_level UNIQUE (schedule_id, level)
);

CREATE TABLE oncall_rotation_member (
    rotation_id UUID NOT NULL REFERENCES oncall_rotation(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    PRIMARY KEY (rotation_id, position),
    CONSTRAINT ck_oncall_rotation_member_position CHECK (position >= 0)
);

-- 3. Garde : une personne, un niveau, une fenêtre [starts_at, ends_at[. Les chevauchements sont
--    permis ; la résolution est déterministe (remplacement d'abord, puis la garde la plus récente).
CREATE TABLE oncall_shift (
    id UUID PRIMARY KEY,
    schedule_id UUID NOT NULL REFERENCES oncall_schedule(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    level VARCHAR(16) NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    note VARCHAR(500),
    override BOOLEAN NOT NULL DEFAULT FALSE,
    -- Garde générée par une rotation (nulle pour une garde saisie à la main).
    rotation_id UUID REFERENCES oncall_rotation(id) ON DELETE SET NULL,
    -- Garde remplacée par ce remplacement (passation), pour l'historique.
    replaces_shift_id UUID REFERENCES oncall_shift(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(255),
    CONSTRAINT ck_oncall_shift_level CHECK (level IN ('PRIMARY', 'SECONDARY')),
    CONSTRAINT ck_oncall_shift_window CHECK (ends_at > starts_at)
);
CREATE INDEX idx_oncall_shift_window ON oncall_shift(schedule_id, level, starts_at, ends_at);
CREATE INDEX idx_oncall_shift_user ON oncall_shift(user_id, starts_at);
-- Idempotence de la génération : un seul créneau généré par rotation et par début.
CREATE UNIQUE INDEX uq_oncall_shift_rotation_slot ON oncall_shift(rotation_id, starts_at)
    WHERE rotation_id IS NOT NULL;

-- 4. Étapes de la chaîne d'escalade d'une alerte : chaque niveau une seule fois par alerte.
CREATE TABLE oncall_alert_step (
    id UUID PRIMARY KEY,
    alert_id UUID NOT NULL REFERENCES alert(id) ON DELETE CASCADE,
    level VARCHAR(16) NOT NULL,
    user_id UUID REFERENCES app_user(id) ON DELETE SET NULL,
    outcome VARCHAR(16) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_oncall_alert_step_level CHECK (level IN ('PRIMARY', 'SECONDARY')),
    CONSTRAINT ck_oncall_alert_step_outcome
        CHECK (outcome IN ('NOTIFIED', 'NO_CHANNEL', 'OUT_OF_SCOPE', 'NOBODY')),
    CONSTRAINT uq_oncall_alert_step UNIQUE (alert_id, level)
);

-- 5. Modèle du message envoyé à la personne d'astreinte (modifiable comme les autres).
ALTER TABLE notification_template DROP CONSTRAINT ck_notification_template_kind;
ALTER TABLE notification_template ADD CONSTRAINT ck_notification_template_kind
    CHECK (kind IN ('ALERT_OPENED', 'ALERT_RESOLVED', 'ALERT_ESCALATED', 'ALERT_ONCALL'));
INSERT INTO notification_template(kind, subject_template, body_template) VALUES
('ALERT_ONCALL', 'Vous êtes d''astreinte : alerte {severite} — {equipement}',
 E'Vous êtes d''astreinte : alerte {severite} sur {equipement} (site : {site}).\n\nMessage : {message}\nDéclenchée le : {declenchee}\n\nPrenez-la en charge dans Trans-DIC1 pour arrêter l''escalade : {url}/alertes\n\n— Trans-DIC1, supervision EPT\n');
