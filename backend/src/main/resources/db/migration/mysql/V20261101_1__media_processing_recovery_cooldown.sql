ALTER TABLE media_request_outbox
    ADD COLUMN processing_recovery_lease_hash VARCHAR(64) NULL,
    ADD COLUMN processing_recovery_next_at TIMESTAMP(6) NULL;
