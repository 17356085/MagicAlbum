ALTER TABLE notification_outbox
    ADD COLUMN request_id VARCHAR(128) NULL AFTER event_id;

ALTER TABLE notification_outbox
    ADD INDEX idx_notification_outbox_status_created_at (status, created_at);
