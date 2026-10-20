-- Derived summaries without a content/task version are intentionally invalidated.
-- The old application must be stopped before enabling real, explicitly requested tasks.
ALTER TABLE threads ADD COLUMN summary_task_id VARCHAR(36) NULL;
UPDATE threads SET summary=NULL,summary_status='IDLE',summary_owner=NULL,
    summary_lease_until=NULL,summary_attempts=0;

CREATE TABLE ai_summary_task (
    task_id VARCHAR(36) NOT NULL,
    thread_id BIGINT NOT NULL,
    content_version BIGINT NOT NULL,
    triggered_by BIGINT NOT NULL,
    request_key VARCHAR(36) NOT NULL,
    force_requested BOOLEAN NOT NULL,
    input_sha256 VARCHAR(64) NOT NULL,
    input_chars INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    lease_owner VARCHAR(36) NULL,
    lease_until TIMESTAMP(6) NULL,
    available_at TIMESTAMP(6) NOT NULL,
    error_code VARCHAR(32) NULL,
    summary TEXT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY(task_id),
    UNIQUE KEY uk_summary_request(triggered_by,request_key),
    KEY idx_summary_recovery(status,available_at,task_id),
    KEY idx_summary_thread(thread_id,content_version),
    KEY idx_summary_actor(triggered_by,status),
    CONSTRAINT fk_summary_task_thread FOREIGN KEY(thread_id) REFERENCES threads(id),
    CONSTRAINT fk_summary_task_actor FOREIGN KEY(triggered_by) REFERENCES users(id),
    CONSTRAINT chk_summary_task_attempts CHECK(attempts BETWEEN 0 AND 3),
    CONSTRAINT chk_summary_task_input CHECK(input_chars BETWEEN 1 AND 12000)
) ENGINE=InnoDB;

CREATE TABLE ai_summary_request (
    triggered_by BIGINT NOT NULL,
    request_key VARCHAR(36) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    thread_id BIGINT NOT NULL,
    content_version BIGINT NOT NULL,
    force_requested BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY(triggered_by,request_key),
    KEY idx_summary_request_task(task_id),
    CONSTRAINT fk_summary_request_task FOREIGN KEY(task_id) REFERENCES ai_summary_task(task_id),
    CONSTRAINT fk_summary_request_thread FOREIGN KEY(thread_id) REFERENCES threads(id),
    CONSTRAINT fk_summary_request_actor FOREIGN KEY(triggered_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE ai_summary_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(36) NOT NULL,
    attempt_number INT NOT NULL,
    lease_owner VARCHAR(36) NOT NULL,
    budget_day DATE NOT NULL,
    reserved_tokens BIGINT NOT NULL,
    charged_tokens BIGINT NOT NULL,
    usage_estimated BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL,
    provider VARCHAR(128) NOT NULL,
    model VARCHAR(256) NOT NULL,
    response_model VARCHAR(256) NULL,
    provider_request_id VARCHAR(128) NULL,
    provider_response_id VARCHAR(128) NULL,
    input_tokens BIGINT NULL,
    output_tokens BIGINT NULL,
    duration_millis BIGINT NOT NULL,
    error_code VARCHAR(32) NULL,
    started_at TIMESTAMP(6) NOT NULL,
    finished_at TIMESTAMP(6) NULL,
    PRIMARY KEY(id),
    UNIQUE KEY uk_summary_attempt(task_id,attempt_number),
    CONSTRAINT fk_summary_attempt_task FOREIGN KEY(task_id) REFERENCES ai_summary_task(task_id),
    CONSTRAINT chk_summary_attempt_charge CHECK(reserved_tokens>=0 AND charged_tokens>=0),
    CONSTRAINT chk_summary_attempt_number CHECK(attempt_number BETWEEN 1 AND 3)
) ENGINE=InnoDB;

CREATE TABLE ai_summary_budget (
    user_id BIGINT NOT NULL,
    budget_day DATE NOT NULL,
    charged_tokens BIGINT NOT NULL,
    PRIMARY KEY(user_id,budget_day),
    CONSTRAINT fk_summary_budget_user FOREIGN KEY(user_id) REFERENCES users(id),
    CONSTRAINT chk_summary_budget_charge CHECK(charged_tokens>=0)
) ENGINE=InnoDB;
