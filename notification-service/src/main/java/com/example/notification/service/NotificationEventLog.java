package com.example.notification.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Per-owner allocation serializes sequence and commit order, unlike AUTO_INCREMENT ids. */
@Service
public class NotificationEventLog {
    private final JdbcTemplate jdbc;

    public NotificationEventLog(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(Long userId, Long notificationId, String kind) {
        jdbc.update("INSERT INTO notification_stream_cursor(user_id,next_sequence) VALUES (?,0) "
                + "ON DUPLICATE KEY UPDATE next_sequence=next_sequence", userId);
        Long previous = jdbc.queryForObject("SELECT next_sequence FROM notification_stream_cursor WHERE user_id=? FOR UPDATE", Long.class, userId);
        long sequence = Math.addExact(previous == null ? 0 : previous, 1);
        jdbc.update("UPDATE notification_stream_cursor SET next_sequence=? WHERE user_id=?", sequence, userId);
        jdbc.update("INSERT INTO notification_stream_event(user_id,sequence_id,notification_id,event_kind,occurred_at) VALUES (?,?,?,?,?)",
                userId, sequence, notificationId, kind, Timestamp.from(Instant.now()));
    }

    public long current(Long userId) {
        return jdbc.query("SELECT next_sequence FROM notification_stream_cursor WHERE user_id=?",
                (rs, row) -> rs.getLong(1), userId).stream().findFirst().orElse(0L);
    }

    public List<Event> after(Long userId, long after, int limit) {
        if (after < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid notification cursor");
        return jdbc.query("""
                SELECT sequence_id,notification_id,event_kind FROM notification_stream_event
                WHERE user_id=? AND sequence_id>? ORDER BY sequence_id LIMIT ?
                """, (rs, row) -> new Event(Long.toString(rs.getLong(1)), Long.toString(rs.getLong(2)), rs.getString(3)), userId, after, limit);
    }

    public record Event(String cursor, String notificationId, String kind) {}
}
