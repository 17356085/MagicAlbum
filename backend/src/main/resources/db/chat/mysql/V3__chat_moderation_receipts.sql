-- Durable receipt ledger for core-owned chat moderation decisions.
-- The decision and message/version keys make delivery idempotent and ordered
-- without copying the core report data into the chat database.
CREATE TABLE chat_moderation_receipts (
    decision_id CHAR(36) NOT NULL,
    message_id BIGINT NOT NULL,
    report_id BIGINT NOT NULL,
    decision_version BIGINT NOT NULL,
    hidden BOOLEAN NOT NULL,
    outcome VARCHAR(8) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (decision_id),
    CONSTRAINT uq_chat_moderation_message_version UNIQUE (message_id, decision_version),
    CONSTRAINT ck_chat_moderation_version_positive CHECK (decision_version > 0),
    CONSTRAINT ck_chat_moderation_message_positive CHECK (message_id > 0),
    CONSTRAINT ck_chat_moderation_report_positive CHECK (report_id > 0),
    CONSTRAINT ck_chat_moderation_outcome CHECK (outcome IN ('APPLIED', 'STALE'))
);
