CREATE TABLE social_friend_requests (
    low_id BIGINT NOT NULL,
    high_id BIGINT NOT NULL,
    requester_id BIGINT NOT NULL,
    state VARCHAR(12) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    decided_at TIMESTAMP(6) NULL,
    PRIMARY KEY (low_id, high_id),
    CONSTRAINT fk_friend_pair_low FOREIGN KEY (low_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_friend_pair_high FOREIGN KEY (high_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_friend_pair CHECK (low_id < high_id AND requester_id IN (low_id, high_id)),
    CONSTRAINT chk_friend_state CHECK (state IN ('PENDING','ACCEPTED','DECLINED')),
    INDEX idx_friend_requests_high (high_id, state, low_id)
);
