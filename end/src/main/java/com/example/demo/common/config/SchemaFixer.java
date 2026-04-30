package com.example.demo.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.schema-fixer.enabled", havingValue = "true", matchIfMissing = true)
public class SchemaFixer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaFixer.class);
    private final JdbcTemplate jdbcTemplate;

    public SchemaFixer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("Checking database schema for missing tables and columns...");
        try {
            // Check if 'summary' column exists in 'threads' table
            Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'threads' AND column_name = 'summary' AND table_schema = DATABASE()",
                Integer.class
            );

            if (count != null && count == 0) {
                log.info("Column 'summary' missing in 'threads' table. Adding it now...");
                jdbcTemplate.execute("ALTER TABLE threads ADD COLUMN summary TEXT");
                jdbcTemplate.execute("ALTER TABLE threads ADD COLUMN summary_status VARCHAR(32) DEFAULT 'PENDING'");
                jdbcTemplate.execute("UPDATE threads SET summary_status = 'PENDING' WHERE summary IS NULL");
                log.info("Schema fix applied successfully.");
            } else {
                log.info("Column 'summary' already exists. No action needed.");
            }

            Integer connectedAccountsTableCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'user_connected_accounts'",
                Integer.class
            );

            if (connectedAccountsTableCount != null && connectedAccountsTableCount == 0) {
                log.info("Table 'user_connected_accounts' is missing. Creating it now...");
                jdbcTemplate.execute("""
                    CREATE TABLE user_connected_accounts (
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
                    )
                    """);
                log.info("Table 'user_connected_accounts' created successfully.");
            } else {
                log.info("Table 'user_connected_accounts' already exists. No action needed.");
            }

        } catch (Exception e) {
            log.error("Failed to check or fix schema: " + e.getMessage());
            // Don't fail startup, just log error
        }
    }
}
