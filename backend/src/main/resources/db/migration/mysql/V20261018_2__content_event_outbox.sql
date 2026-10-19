ALTER TABLE threads
    ADD COLUMN content_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE content_event_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(36) NOT NULL,
    schema_version INT NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    request_id VARCHAR(128) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    PRIMARY KEY (id),
    CONSTRAINT uk_content_event_outbox_event_id UNIQUE (event_id),
    CONSTRAINT uk_content_event_outbox_aggregate_version UNIQUE (aggregate_id, aggregate_version)
) ENGINE=InnoDB;

CREATE INDEX idx_content_event_outbox_status_id
    ON content_event_outbox (status, id);
