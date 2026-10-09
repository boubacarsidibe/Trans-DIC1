CREATE TABLE equipment (
    id UUID PRIMARY KEY,
    inventory_code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    equipment_type VARCHAR(32) NOT NULL,
    management_address VARCHAR(45) NOT NULL UNIQUE,
    operational_status VARCHAR(32) NOT NULL,
    site VARCHAR(120) NOT NULL,
    is_fictitious BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT equipment_type_check CHECK (
        equipment_type IN ('SERVER', 'ROUTER', 'SWITCH', 'VIRTUAL_MACHINE', 'WIFI_ACCESS_POINT')
    ),
    CONSTRAINT equipment_status_check CHECK (
        operational_status IN ('UP', 'DOWN', 'DEGRADED', 'MAINTENANCE', 'UNKNOWN')
    ),
    CONSTRAINT fictitious_equipment_only_check CHECK (is_fictitious = TRUE)
);

-- Les blocs TEST-NET de la RFC 5737 garantissent que ces adresses ne désignent
-- aucun équipement réel. L'insertion conditionnelle rend le jeu idempotent.
INSERT INTO equipment (
    id, inventory_code, name, equipment_type, management_address,
    operational_status, site, is_fictitious
)
SELECT
    CAST('00000000-0000-0000-0000-000000000001' AS UUID),
    'DEMO-SRV-001', 'Serveur de démonstration', 'SERVER', '192.0.2.10',
    'UP', 'LAB-FICTIF-A', TRUE
WHERE NOT EXISTS (SELECT 1 FROM equipment WHERE inventory_code = 'DEMO-SRV-001');

INSERT INTO equipment (
    id, inventory_code, name, equipment_type, management_address,
    operational_status, site, is_fictitious
)
SELECT
    CAST('00000000-0000-0000-0000-000000000002' AS UUID),
    'DEMO-RTR-001', 'Routeur de démonstration', 'ROUTER', '192.0.2.20',
    'DEGRADED', 'LAB-FICTIF-A', TRUE
WHERE NOT EXISTS (SELECT 1 FROM equipment WHERE inventory_code = 'DEMO-RTR-001');

INSERT INTO equipment (
    id, inventory_code, name, equipment_type, management_address,
    operational_status, site, is_fictitious
)
SELECT
    CAST('00000000-0000-0000-0000-000000000003' AS UUID),
    'DEMO-SWT-001', 'Commutateur de démonstration', 'SWITCH', '198.51.100.10',
    'DOWN', 'LAB-FICTIF-B', TRUE
WHERE NOT EXISTS (SELECT 1 FROM equipment WHERE inventory_code = 'DEMO-SWT-001');

INSERT INTO equipment (
    id, inventory_code, name, equipment_type, management_address,
    operational_status, site, is_fictitious
)
SELECT
    CAST('00000000-0000-0000-0000-000000000004' AS UUID),
    'DEMO-VM-001', 'Machine virtuelle de démonstration', 'VIRTUAL_MACHINE', '198.51.100.20',
    'MAINTENANCE', 'LAB-FICTIF-B', TRUE
WHERE NOT EXISTS (SELECT 1 FROM equipment WHERE inventory_code = 'DEMO-VM-001');

INSERT INTO equipment (
    id, inventory_code, name, equipment_type, management_address,
    operational_status, site, is_fictitious
)
SELECT
    CAST('00000000-0000-0000-0000-000000000005' AS UUID),
    'DEMO-WAP-001', 'Point d accès Wi-Fi de démonstration', 'WIFI_ACCESS_POINT', '203.0.113.10',
    'UNKNOWN', 'LAB-FICTIF-C', TRUE
WHERE NOT EXISTS (SELECT 1 FROM equipment WHERE inventory_code = 'DEMO-WAP-001');
