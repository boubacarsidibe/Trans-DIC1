-- Verrou des tâches planifiées : une seule instance exécute chaque tâche à un instant donné.
CREATE TABLE job_lock (
    name         VARCHAR(100) PRIMARY KEY,
    locked_until TIMESTAMPTZ  NOT NULL,
    locked_at    TIMESTAMPTZ  NOT NULL,
    locked_by    VARCHAR(100) NOT NULL
);

-- État de la dernière exécution et compteurs cumulés, pour savoir si une tâche tourne bien.
CREATE TABLE job_run (
    name             VARCHAR(100) PRIMARY KEY,
    last_status      VARCHAR(20)  NOT NULL,
    last_started_at  TIMESTAMPTZ  NOT NULL,
    last_duration_ms BIGINT       NOT NULL,
    success_count    BIGINT       NOT NULL DEFAULT 0,
    failure_count    BIGINT       NOT NULL DEFAULT 0,
    skipped_count    BIGINT       NOT NULL DEFAULT 0
);
