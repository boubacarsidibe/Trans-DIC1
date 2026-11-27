-- EDR léger : télémétrie de sécurité des postes envoyée par l'agent (ADR 0058).

-- Profil de sécurité d'un équipement : son premier rapport fixe la ligne de base.
CREATE TABLE endpoint_profile
(
    equipment_id   UUID PRIMARY KEY REFERENCES equipment (id) ON DELETE CASCADE,
    os             VARCHAR(16) NOT NULL,
    baseline_at    TIMESTAMPTZ NOT NULL,
    last_report_at TIMESTAMPTZ NOT NULL,
    reports        INTEGER     NOT NULL DEFAULT 1 CHECK (reports >= 1),
    partial        BOOLEAN     NOT NULL DEFAULT FALSE
);

-- Ligne de base : ce qui démarre seul, les comptes, l'empreinte des fichiers sensibles.
-- Un élément absent de la ligne de base au rapport suivant est un changement à signaler.
CREATE TABLE endpoint_baseline
(
    equipment_id  UUID         NOT NULL REFERENCES equipment (id) ON DELETE CASCADE,
    category      VARCHAR(16)  NOT NULL CHECK (category IN ('PERSISTENCE', 'ACCOUNT', 'INTEGRITY')),
    item_key      VARCHAR(400) NOT NULL,
    fingerprint   VARCHAR(64),
    first_seen_at TIMESTAMPTZ  NOT NULL,
    last_seen_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (equipment_id, category, item_key)
);

-- Constat : un indicateur ou un changement, rattaché à une technique MITRE ATT&CK, puis jugé par
-- un opérateur (légitime ou malveillant). Un même sujet ne donne qu'un constat : un changement
-- légitime ne revient pas, un nouveau changement du même fichier a un autre sujet.
CREATE TABLE endpoint_finding
(
    id             UUID PRIMARY KEY,
    equipment_id   UUID         NOT NULL REFERENCES equipment (id) ON DELETE CASCADE,
    rule           VARCHAR(40)  NOT NULL,
    technique_id   VARCHAR(16),
    technique_name VARCHAR(120),
    tactic         VARCHAR(60),
    severity       VARCHAR(16)  NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    subject        VARCHAR(400) NOT NULL,
    detail         VARCHAR(500),
    state          VARCHAR(16)  NOT NULL CHECK (state IN ('OPEN', 'LEGITIMATE', 'MALICIOUS')),
    detected_at    TIMESTAMPTZ  NOT NULL,
    last_seen_at   TIMESTAMPTZ  NOT NULL,
    decided_by     VARCHAR(64),
    decided_at     TIMESTAMPTZ,
    CONSTRAINT ux_endpoint_finding_subject UNIQUE (equipment_id, rule, subject)
);

CREATE INDEX ix_endpoint_finding_open ON endpoint_finding (detected_at DESC) WHERE state = 'OPEN';

COMMENT ON TABLE endpoint_finding IS
    'Constats de l''EDR léger : indicateurs et changements suspects sur les postes (ADR 0058)';
