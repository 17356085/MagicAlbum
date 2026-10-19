ALTER TABLE media_request_outbox
    ADD COLUMN attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN available_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN lease_owner VARCHAR(64) NULL,
    ADD COLUMN lease_until TIMESTAMP(6) NULL,
    ADD COLUMN last_error VARCHAR(64) NULL;
ALTER TABLE media_request_outbox DROP CHECK chk_media_request_outbox_status;
ALTER TABLE media_request_outbox ADD CONSTRAINT chk_media_request_outbox_status
    CHECK (status IN ('PENDING','IN_FLIGHT','PUBLISHED','FAILED'));
CREATE INDEX idx_media_outbox_due ON media_request_outbox(status, available_at, event_id);
CREATE INDEX idx_media_outbox_expired ON media_request_outbox(status, lease_until, event_id);
