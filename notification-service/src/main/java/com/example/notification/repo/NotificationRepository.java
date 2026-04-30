package com.example.notification.repo;

import com.example.notification.entity.Notification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class NotificationRepository {
    private static final RowMapper<Notification> ROW_MAPPER = (rs, rowNum) -> {
        Notification notification = new Notification();
        notification.setId(rs.getLong("id"));
        notification.setUserId(rs.getLong("user_id"));
        notification.setType(rs.getString("type"));
        notification.setTitle(rs.getString("title"));
        notification.setContent(rs.getString("content"));
        notification.setRead(rs.getBoolean("read_flag"));
        notification.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return notification;
    };

    private final JdbcTemplate jdbcTemplate;

    public NotificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public NotificationPage find(Long userId, String type, Boolean unread, int offset, int limit) {
        QueryParts query = buildQuery(userId, type, unread);
        List<Object> listArgs = new ArrayList<>(query.args());
        listArgs.add(limit);
        listArgs.add(offset);

        List<Notification> items = jdbcTemplate.query(
                "SELECT id, user_id, type, title, content, read_flag, created_at FROM notifications "
                        + query.whereClause()
                        + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER,
                listArgs.toArray()
        );
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications " + query.whereClause(),
                Long.class,
                query.args().toArray()
        );
        return new NotificationPage(items, total == null ? 0 : total);
    }

    public Optional<Notification> findByIdAndUserId(Long id, Long userId) {
        List<Notification> matches = jdbcTemplate.query(
                "SELECT id, user_id, type, title, content, read_flag, created_at FROM notifications WHERE id = ? AND user_id = ?",
                ROW_MAPPER,
                id,
                userId
        );
        return matches.stream().findFirst();
    }

    public void markRead(Long id, Long userId) {
        jdbcTemplate.update("UPDATE notifications SET read_flag = TRUE WHERE id = ? AND user_id = ?", id, userId);
    }

    public Notification save(Notification notification) {
        Instant createdAt = notification.getCreatedAt() == null ? Instant.now() : notification.getCreatedAt();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO notifications (user_id, type, title, content, read_flag, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, notification.getUserId());
            ps.setString(2, notification.getType());
            ps.setString(3, notification.getTitle());
            ps.setString(4, notification.getContent());
            ps.setBoolean(5, notification.isRead());
            ps.setTimestamp(6, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key != null) {
            notification.setId(key.longValue());
        }
        notification.setCreatedAt(createdAt);
        return notification;
    }

    private QueryParts buildQuery(Long userId, String type, Boolean unread) {
        List<String> clauses = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        clauses.add("user_id = ?");
        args.add(userId);

        if (type != null && !type.isBlank()) {
            clauses.add("LOWER(type) = LOWER(?)");
            args.add(type.trim());
        }
        if (Boolean.TRUE.equals(unread)) {
            clauses.add("read_flag = FALSE");
        }
        return new QueryParts("WHERE " + String.join(" AND ", clauses), args);
    }

    public record NotificationPage(List<Notification> items, long total) {
    }

    private record QueryParts(String whereClause, List<Object> args) {
    }
}
