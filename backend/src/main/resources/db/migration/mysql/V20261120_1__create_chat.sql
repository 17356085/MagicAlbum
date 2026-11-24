CREATE TABLE chat_conversations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    direct_low_id BIGINT,
    direct_high_id BIGINT,
    requester_id BIGINT,
    owner_id BIGINT,
    title VARCHAR(80),
    state VARCHAR(12) NOT NULL,
    last_seq BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_chat_direct_pair UNIQUE (direct_low_id, direct_high_id)
);

CREATE TABLE chat_members (
    conversation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    state VARCHAR(12) NOT NULL,
    invited_at TIMESTAMP(6),
    visible_from_seq BIGINT NOT NULL DEFAULT 1,
    last_read_seq BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (conversation_id, user_id),
    CONSTRAINT fk_chat_member_room FOREIGN KEY (conversation_id) REFERENCES chat_conversations(id)
);

CREATE INDEX ix_chat_member_user ON chat_members (user_id, state, conversation_id);

CREATE TABLE chat_messages (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    seq BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    client_message_id CHAR(36) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    hidden BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_chat_message_room FOREIGN KEY (conversation_id) REFERENCES chat_conversations(id),
    CONSTRAINT uq_chat_message_seq UNIQUE (conversation_id, seq),
    CONSTRAINT uq_chat_message_request UNIQUE (conversation_id, sender_id, client_message_id)
);

CREATE TABLE chat_daily_usage (
    user_id BIGINT NOT NULL,
    quota_date DATE NOT NULL,
    used_count INT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, quota_date)
);

CREATE TABLE chat_preferences (
    user_id BIGINT NOT NULL PRIMARY KEY,
    allow_requests BOOLEAN NOT NULL
);

CREATE TABLE chat_blocks (
    blocker_id BIGINT NOT NULL,
    blocked_id BIGINT NOT NULL,
    PRIMARY KEY (blocker_id, blocked_id)
);

CREATE INDEX ix_chat_blocks_recipient ON chat_blocks (blocked_id, blocker_id);
