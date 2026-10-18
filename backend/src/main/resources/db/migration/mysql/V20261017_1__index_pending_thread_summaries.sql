CREATE INDEX idx_threads_summary_recovery
    ON threads (status, summary_status, id, created_at);
