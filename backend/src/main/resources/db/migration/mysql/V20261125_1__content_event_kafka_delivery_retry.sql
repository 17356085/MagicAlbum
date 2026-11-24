ALTER TABLE content_event_kafka_delivery
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0 AFTER outbox_id,
    ADD COLUMN failed_at DATETIME(6) NULL AFTER published_at,
    ADD INDEX idx_kafka_delivery_claim_state (published_at, failed_at, available_at, outbox_id),
    ADD INDEX idx_kafka_delivery_failed (failed_at, outbox_id);
