CREATE TABLE metric (
    id UUID PRIMARY KEY,
    equipment_id UUID NOT NULL REFERENCES equipment(id),
    metric_key VARCHAR(64) NOT NULL,
    metric_value NUMERIC(12, 4) NOT NULL,
    unit VARCHAR(24) NOT NULL,
    quality VARCHAR(24) NOT NULL,
    collected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_metric_equipment_collected
    ON metric(equipment_id, collected_at DESC);

INSERT INTO metric (
    id, equipment_id, metric_key, metric_value, unit, quality, collected_at
)
VALUES (
    CAST('20000000-0000-0000-0000-000000000001' AS UUID),
    CAST('00000000-0000-0000-0000-000000000001' AS UUID),
    'cpu.usage',
    42.5000,
    '%',
    'GOOD',
    CURRENT_TIMESTAMP
);
