-- Bibliothèque de modèles d'équipement (ADR 0022).
--
-- 1. Valeur dérivée d'un indicateur SNMP : valeur stockée = brute × scale + value_offset, puis
--    100 - valeur si invert_percent (ex. ssCpuIdle -> charge CPU). Sans ces champs, la valeur brute
--    est stockée telle quelle, comme avant.
ALTER TABLE metric_definition ADD COLUMN scale NUMERIC(20, 10);
ALTER TABLE metric_definition ADD COLUMN value_offset NUMERIC(20, 4);
ALTER TABLE metric_definition ADD COLUMN invert_percent BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE metric_definition ADD CONSTRAINT ck_metric_definition_scale
    CHECK (scale IS NULL OR scale > 0);

-- 2. Port SNMP propre à une configuration (nul : port par défaut collection.snmp.default-port).
ALTER TABLE collection_configuration ADD COLUMN snmp_port INTEGER;
ALTER TABLE collection_configuration ADD CONSTRAINT ck_collection_configuration_snmp_port
    CHECK (snmp_port IS NULL OR snmp_port BETWEEN 1 AND 65535);

-- 3. Modèles appliqués à un équipement : un enregistrement par (équipement, modèle), mis à jour à
--    chaque nouvelle application (version, date, auteur). Appliquer un autre modèle ajoute une ligne.
CREATE TABLE equipment_template (
    id UUID PRIMARY KEY,
    equipment_id UUID NOT NULL REFERENCES equipment(id) ON DELETE CASCADE,
    template_id VARCHAR(64) NOT NULL,
    template_version INTEGER NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL,
    applied_by VARCHAR(255),
    CONSTRAINT uq_equipment_template UNIQUE (equipment_id, template_id),
    CONSTRAINT ck_equipment_template_version CHECK (template_version >= 1)
);

CREATE INDEX idx_equipment_template_equipment ON equipment_template(equipment_id);
