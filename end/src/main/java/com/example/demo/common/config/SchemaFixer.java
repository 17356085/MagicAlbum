package com.example.demo.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SchemaFixer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaFixer.class);
    private final JdbcTemplate jdbcTemplate;

    public SchemaFixer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("Checking database schema for missing columns...");
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
        } catch (Exception e) {
            log.error("Failed to check or fix schema: " + e.getMessage());
            // Don't fail startup, just log error
        }
    }
}
