-- Correlation apprise entre equipements (M9, ADR 0042).
--
-- Deux equipements dont les alertes arrivent ensemble, encore et encore, sont probablement lies par
-- quelque chose que la topologie ne sait pas : un serveur et le stockage qu'il monte, une baie et
-- son onduleur, deux machines derriere le meme cordon mal declare. La plateforme ne fait que le
-- proposer ; un administrateur accepte ou ecarte, et ce n'est qu'accepte que le lien sert.
--
-- La paire est rangee dans un ordre canonique (le plus petit identifiant d'abord) : sans cela, la
-- meme paire existerait deux fois et serait proposee deux fois.
CREATE TABLE learned_correlation (
    id UUID PRIMARY KEY,
    first_equipment_id UUID NOT NULL REFERENCES equipment(id) ON DELETE CASCADE,
    second_equipment_id UUID NOT NULL REFERENCES equipment(id) ON DELETE CASCADE,
    -- Fenetres de temps ou les deux ont eu une alerte, et fenetres ou chacun en a eu une.
    together INTEGER NOT NULL,
    first_windows INTEGER NOT NULL,
    second_windows INTEGER NOT NULL,
    total_windows INTEGER NOT NULL,
    -- Regles d'association : support = part des fenetres ou les deux apparaissent ; confiance =
    -- part des fenetres de l'un ou l'autre apparait aussi, la plus forte des deux directions.
    support NUMERIC(6, 5) NOT NULL,
    confidence NUMERIC(6, 5) NOT NULL,
    state VARCHAR(16) NOT NULL,
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_computed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_at TIMESTAMP WITH TIME ZONE,
    decided_by VARCHAR(120),
    CONSTRAINT uq_learned_correlation UNIQUE (first_equipment_id, second_equipment_id),
    CONSTRAINT ck_learned_correlation_order CHECK (first_equipment_id < second_equipment_id),
    CONSTRAINT ck_learned_correlation_state CHECK (state IN ('PROPOSED', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_learned_correlation_counts CHECK (
        together > 0 AND together <= first_windows AND together <= second_windows
        AND first_windows <= total_windows AND second_windows <= total_windows),
    CONSTRAINT ck_learned_correlation_decided CHECK (
        (state = 'PROPOSED') = (decided_at IS NULL))
);

CREATE INDEX idx_learned_correlation_state ON learned_correlation(state, confidence DESC);
CREATE INDEX idx_learned_correlation_first ON learned_correlation(first_equipment_id);
CREATE INDEX idx_learned_correlation_second ON learned_correlation(second_equipment_id);
