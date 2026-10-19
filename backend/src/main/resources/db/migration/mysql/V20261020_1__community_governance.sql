-- M4A community governance policy and append-only audit trail.
ALTER TABLE sections
    ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS community_state (
    id INT NOT NULL,
    bootstrapped BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    CONSTRAINT chk_community_state_singleton CHECK (id = 1)
) ENGINE = InnoDB;

INSERT INTO community_state (id, bootstrapped)
VALUES (1, FALSE)
ON DUPLICATE KEY UPDATE id = id;

CREATE TABLE IF NOT EXISTS community_admin_role (
    user_id BIGINT NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_community_admin_role_user
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS community_moderator_scope (
    user_id BIGINT NOT NULL,
    section_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, section_id),
    KEY idx_community_moderator_scope_section (section_id),
    CONSTRAINT fk_community_moderator_scope_user
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_community_moderator_scope_section
        FOREIGN KEY (section_id) REFERENCES sections (id)
        ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS community_posting_restriction (
    user_id BIGINT NOT NULL,
    restricted_until TIMESTAMP(6) NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_community_posting_restriction_user
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS community_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_id BIGINT NULL,
    action VARCHAR(128) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id BIGINT NULL,
    reason VARCHAR(500) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    request_id VARCHAR(128) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_community_audit_occurred (occurred_at, id),
    KEY idx_community_audit_actor (actor_id),
    CONSTRAINT chk_community_audit_reason CHECK (CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 500)
) ENGINE = InnoDB;
