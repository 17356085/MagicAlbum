package com.example.notification.service;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Database-specific initialization for the per-owner notification stream cursor.
 */
public interface NotificationStreamCursorDialect {
    void ensureCursor(JdbcTemplate jdbc, Long userId);
}
