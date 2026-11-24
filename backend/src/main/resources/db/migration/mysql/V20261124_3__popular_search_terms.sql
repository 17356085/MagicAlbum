CREATE TABLE popular_search_terms (
    id BIGINT NOT NULL AUTO_INCREMENT,
    search_type VARCHAR(16) NOT NULL,
    account_id BIGINT NOT NULL,
    keyword VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    normalized_keyword VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    search_day DATE NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT chk_popular_search_terms_type
        CHECK (search_type IN ('threads', 'users')),
    CONSTRAINT fk_popular_search_terms_account
        FOREIGN KEY (account_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_popular_search_terms_account_day
        UNIQUE (account_id, search_type, normalized_keyword, search_day),
    INDEX idx_popular_search_terms_window
        (search_type, search_day, normalized_keyword, account_id),
    INDEX idx_popular_search_terms_expiry (search_day, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
