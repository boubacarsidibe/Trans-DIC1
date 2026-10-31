-- Topologie : un équipement dépend d'un équipement amont (son lien vers le cœur du réseau).
-- La panne de l'amont rend injoignables tous les équipements situés en aval.
CREATE TABLE equipment_link (
    id            UUID PRIMARY KEY,
    equipment_id  UUID NOT NULL,
    upstream_id   UUID NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_equipment_link_equipment FOREIGN KEY (equipment_id) REFERENCES equipment(id) ON DELETE CASCADE,
    CONSTRAINT fk_equipment_link_upstream FOREIGN KEY (upstream_id) REFERENCES equipment(id) ON DELETE CASCADE,
    CONSTRAINT uq_equipment_link UNIQUE (equipment_id, upstream_id),
    CONSTRAINT ck_equipment_link_not_self CHECK (equipment_id <> upstream_id)
);
CREATE INDEX idx_equipment_link_upstream ON equipment_link(upstream_id);

-- Liens de démonstration entre les équipements fictifs, s'ils existent.
INSERT INTO equipment_link(id, equipment_id, upstream_id)
SELECT gen_random_uuid(), child.id, parent.id
FROM (VALUES
        ('DEMO-SWT-001', 'DEMO-RTR-001'),
        ('DEMO-SRV-001', 'DEMO-SWT-001'),
        ('DEMO-WAP-001', 'DEMO-SWT-001'),
        ('DEMO-VM-001',  'DEMO-SRV-001')
     ) AS pair(child_code, parent_code)
JOIN equipment child ON child.inventory_code = pair.child_code
JOIN equipment parent ON parent.inventory_code = pair.parent_code
ON CONFLICT DO NOTHING;
