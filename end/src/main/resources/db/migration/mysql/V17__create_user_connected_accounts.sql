CREATE TABLE IF NOT EXISTS user_connected_accounts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    provider VARCHAR(32) NOT NULL,
    external_id VARCHAR(255) NULL,
    display_name VARCHAR(255) NULL,
    linked_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_connected_accounts_user_provider (user_id, provider),
    UNIQUE KEY uk_user_connected_accounts_provider_external (provider, external_id),
    CONSTRAINT fk_user_connected_accounts_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
