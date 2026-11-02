-- Sondes sans agent : paramètres d'exécution et dernier résultat sur chaque service supervisé.
ALTER TABLE monitored_service
    ADD COLUMN interval_seconds INTEGER NOT NULL DEFAULT 60,
    ADD COLUMN timeout_ms INTEGER NOT NULL DEFAULT 5000,
    ADD COLUMN expected_status VARCHAR(16),
    ADD COLUMN expected_text VARCHAR(200),
    ADD COLUMN next_run_at TIMESTAMPTZ,
    ADD COLUMN last_checked_at TIMESTAMPTZ,
    ADD COLUMN last_up BOOLEAN,
    ADD COLUMN last_latency_ms INTEGER,
    ADD COLUMN last_error VARCHAR(200),
    ADD COLUMN consecutive_failures INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_monitored_service_interval CHECK (interval_seconds >= 30),
    ADD CONSTRAINT ck_monitored_service_timeout CHECK (timeout_ms BETWEEN 100 AND 30000);

ALTER TABLE monitored_service DROP CONSTRAINT ck_monitored_service_type;
ALTER TABLE monitored_service ADD CONSTRAINT ck_monitored_service_type
    CHECK (type IN ('TCP_PORT', 'HTTP', 'HTTPS', 'ICMP', 'DNS', 'TLS_CERT', 'OTHER'));

CREATE INDEX idx_monitored_service_due ON monitored_service(next_run_at) WHERE active;
