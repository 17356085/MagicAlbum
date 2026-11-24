CREATE TABLE content_event_kafka_delivery (
    outbox_id BIGINT NOT NULL PRIMARY KEY,
    available_at DATETIME(6) NOT NULL,
    lease_token CHAR(36),
    lease_until DATETIME(6),
    published_at DATETIME(6),
    last_error VARCHAR(64),
    CONSTRAINT fk_kafka_delivery_content_outbox
        FOREIGN KEY (outbox_id) REFERENCES content_event_outbox (id),
    INDEX idx_kafka_delivery_claim (published_at, available_at, outbox_id)
);
