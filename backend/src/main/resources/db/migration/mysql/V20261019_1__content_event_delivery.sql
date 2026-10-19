ALTER TABLE content_event_outbox
    ADD COLUMN attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN available_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN lease_owner VARCHAR(64) NULL,
    ADD COLUMN lease_until TIMESTAMP(6) NULL,
    ADD COLUMN published_at TIMESTAMP(6) NULL,
    ADD COLUMN last_error VARCHAR(64) NULL;

-- Existing durable events are immediately eligible for the first publisher run.
UPDATE content_event_outbox
   SET available_at = COALESCE(occurred_at, CURRENT_TIMESTAMP(6));

CREATE INDEX idx_content_event_outbox_delivery_claim
    ON content_event_outbox (status, available_at, id);

CREATE INDEX idx_content_event_outbox_delivery_lease
    ON content_event_outbox (status, lease_until, id);
