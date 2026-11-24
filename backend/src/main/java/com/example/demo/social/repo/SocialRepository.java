package com.example.demo.social.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Persistence boundary for social-owned relationship state.
 *
 * <p>The chat block table is deliberately kept behind this repository. Chat
 * callers obtain contact decisions from {@code ContactPolicy} instead of
 * issuing SQL against the table themselves.</p>
 */
@Repository
public class SocialRepository {
    private final JdbcTemplate jdbc;

    public SocialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean blockedBy(long blocker, long blocked) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM chat_blocks
                  WHERE blocker_id = ? AND blocked_id = ?)
                """, Boolean.class, blocker, blocked));
    }

    /**
     * Returns both directions of the block relation, without duplicates.
     * The result is ordered to make the value deterministic when it is used
     * to filter a paginated mutual-friends query.
     */
    public List<Long> excluded(long user) {
        return jdbc.queryForList("""
                SELECT blocked_id AS user_id FROM chat_blocks WHERE blocker_id = ?
                UNION
                SELECT blocker_id AS user_id FROM chat_blocks WHERE blocked_id = ?
                ORDER BY user_id
                """, Long.class, user, user);
    }

    public List<Long> blockList(long user) {
        return jdbc.queryForList("""
                SELECT blocked_id FROM chat_blocks
                WHERE blocker_id = ? ORDER BY blocked_id
                """, Long.class, user);
    }

    /**
     * Applies the caller's directed block relation. The caller is expected to
     * hold the social lock set before entering this method; the operation is
     * idempotent for both create and delete.
     */
    public void setBlocked(long blocker, long blocked, boolean value) {
        if (value) {
            if (!blockedBy(blocker, blocked)) {
                jdbc.update("""
                        INSERT INTO chat_blocks (blocker_id, blocked_id)
                        VALUES (?, ?)
                        """, blocker, blocked);
            }
            return;
        }
        jdbc.update("DELETE FROM chat_blocks WHERE blocker_id = ? AND blocked_id = ?", blocker, blocked);
    }
}
