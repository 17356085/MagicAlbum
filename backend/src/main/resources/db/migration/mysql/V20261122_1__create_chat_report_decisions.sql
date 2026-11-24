-- Durable core-side coordination for moderation commands sent to the remote
-- chat owner.  Message IDs are stable cross-service references, not foreign
-- keys into the independently deployed chat database.
CREATE TABLE IF NOT EXISTS chat_report_decision_locks (
    message_id BIGINT NOT NULL,
    next_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (message_id),
    CONSTRAINT chk_chat_report_decision_lock_message CHECK (message_id > 0),
    CONSTRAINT chk_chat_report_decision_lock_version CHECK (next_version >= 0)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS chat_report_decisions (
    decision_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    reviewer_id BIGINT NOT NULL,
    hidden BOOLEAN NOT NULL,
    reason VARCHAR(500) NOT NULL,
    version BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    available_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_until TIMESTAMP(6) NULL,
    last_error_code VARCHAR(32) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (decision_id),
    UNIQUE KEY uk_chat_report_decision_message_version (message_id, version),
    KEY idx_chat_report_decisions_claim (status, available_at, created_at, decision_id),
    KEY idx_chat_report_decisions_report (report_id, status, version),
    KEY idx_chat_report_decisions_message (message_id, status, version),
    CONSTRAINT fk_chat_report_decision_report
        FOREIGN KEY (report_id) REFERENCES content_reports (id) ON DELETE RESTRICT,
    CONSTRAINT fk_chat_report_decision_reviewer
        FOREIGN KEY (reviewer_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_chat_report_decision_report CHECK (report_id > 0),
    CONSTRAINT chk_chat_report_decision_message CHECK (message_id > 0),
    CONSTRAINT chk_chat_report_decision_reviewer CHECK (reviewer_id > 0),
    CONSTRAINT chk_chat_report_decision_version CHECK (version > 0),
    CONSTRAINT chk_chat_report_decision_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_chat_report_decision_status
        CHECK (status IN ('PENDING', 'CLAIMED', 'CONFIRMED', 'FAILED')),
    CONSTRAINT chk_chat_report_decision_error
        CHECK (last_error_code IS NULL OR last_error_code IN ('STALE', 'TARGET_MISSING', 'CONFLICT')),
    CONSTRAINT chk_chat_report_decision_reason
        CHECK (CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 500)
) ENGINE = InnoDB;

-- At most one non-terminal command can be active for a message.  The history
-- row remains in chat_report_decisions after confirmation or permanent failure.
CREATE TABLE IF NOT EXISTS chat_report_decision_active (
    message_id BIGINT NOT NULL,
    report_id BIGINT NOT NULL,
    decision_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (message_id),
    UNIQUE KEY uk_chat_report_decision_active_decision (decision_id),
    KEY idx_chat_report_decision_active_report (report_id),
    CONSTRAINT fk_chat_report_decision_active_decision
        FOREIGN KEY (decision_id) REFERENCES chat_report_decisions (decision_id) ON DELETE RESTRICT,
    CONSTRAINT chk_chat_report_decision_active_message CHECK (message_id > 0),
    CONSTRAINT chk_chat_report_decision_active_report CHECK (report_id > 0),
    CONSTRAINT chk_chat_report_decision_active_version CHECK (version > 0)
) ENGINE = InnoDB;
