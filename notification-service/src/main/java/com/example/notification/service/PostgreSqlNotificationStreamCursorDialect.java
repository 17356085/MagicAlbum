package com.example.notification.service;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL cursor initialization. The conflict is intentionally ignored so
 * concurrent first events for one owner retain the caller transaction.
 */
@Component
@Profile("postgresql")
public class PostgreSqlNotificationStreamCursorDialect implements NotificationStreamCursorDialect {
    @Override
    public void ensureCursor(JdbcTemplate jdbc, Long userId) {
        jdbc.update("INSERT INTO notification_stream_cursor(user_id,next_sequence) VALUES (?,0) "
                + "ON CONFLICT (user_id) DO NOTHING", userId);
    }
}
