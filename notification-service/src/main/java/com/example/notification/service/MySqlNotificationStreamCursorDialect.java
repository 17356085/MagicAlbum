package com.example.notification.service;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * MySQL-compatible cursor initialization used by the default profile and H2 tests.
 */
@Component
@Profile("!postgresql")
public class MySqlNotificationStreamCursorDialect implements NotificationStreamCursorDialect {
    @Override
    public void ensureCursor(JdbcTemplate jdbc, Long userId) {
        jdbc.update("INSERT INTO notification_stream_cursor(user_id,next_sequence) VALUES (?,0) "
                + "ON DUPLICATE KEY UPDATE next_sequence=next_sequence", userId);
    }
}
