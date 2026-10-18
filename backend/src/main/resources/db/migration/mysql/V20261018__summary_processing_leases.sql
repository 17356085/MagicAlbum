ALTER TABLE threads
    ADD COLUMN summary_owner VARCHAR(36) NULL,
    ADD COLUMN summary_lease_until DATETIME(6) NULL,
    ADD COLUMN summary_attempts INT NOT NULL DEFAULT 0;

-- Legacy PROCESSING rows have no lease and are immediately eligible for recovery.
CREATE INDEX idx_threads_summary_lease ON threads(status, summary_status, summary_lease_until, id);
