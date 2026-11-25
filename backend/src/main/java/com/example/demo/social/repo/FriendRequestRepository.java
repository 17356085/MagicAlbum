package com.example.demo.social.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class FriendRequestRepository {
    private static final String RELATIONSHIPS = """
            FROM (
                SELECT outgoing.following_id AS peer_id, outgoing.created_at AS connected_at
                FROM user_follows outgoing
                WHERE outgoing.follower_id = ? AND EXISTS (
                    SELECT 1 FROM user_follows incoming
                    WHERE incoming.follower_id = outgoing.following_id AND incoming.following_id = outgoing.follower_id)
                UNION ALL
                SELECT high_id, decided_at FROM social_friend_requests WHERE low_id = ? AND state = 'ACCEPTED'
                UNION ALL
                SELECT low_id, decided_at FROM social_friend_requests WHERE high_id = ? AND state = 'ACCEPTED'
            ) peers
            WHERE NOT EXISTS (SELECT 1 FROM chat_blocks WHERE blocker_id = ? AND blocked_id = peers.peer_id)
              AND NOT EXISTS (SELECT 1 FROM chat_blocks WHERE blocked_id = ? AND blocker_id = peers.peer_id)
            """;
    private static final String RECEIVED = """
            FROM social_friend_requests request
            WHERE (request.low_id = ? OR request.high_id = ?) AND request.requester_id <> ?
                AND request.state = 'PENDING'
                AND NOT EXISTS (SELECT 1 FROM chat_blocks WHERE blocker_id = ? AND blocked_id = request.requester_id)
                AND NOT EXISTS (SELECT 1 FROM chat_blocks WHERE blocked_id = ? AND blocker_id = request.requester_id)
            """;

    private final JdbcTemplate jdbc;

    public FriendRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Request> find(long first, long second) {
        return jdbc.query("SELECT requester_id,state FROM social_friend_requests WHERE low_id = ? AND high_id = ?",
                (rs, row) -> new Request(rs.getLong(1), State.valueOf(rs.getString(2))),
                Math.min(first, second), Math.max(first, second)).stream().findFirst();
    }

    /** Callers acquire the same account-pair lock used for contact and block decisions. */
    public void send(long requester, long peer, boolean exists) {
        long low = Math.min(requester, peer);
        long high = Math.max(requester, peer);
        if (exists) {
            jdbc.update("""
                    UPDATE social_friend_requests SET requester_id = ?, state = 'PENDING',
                        created_at = CURRENT_TIMESTAMP(6), decided_at = NULL
                    WHERE low_id = ? AND high_id = ?
                    """, requester, low, high);
        } else {
            jdbc.update("INSERT INTO social_friend_requests(low_id,high_id,requester_id,state) VALUES (?,?,?,'PENDING')",
                    low, high, requester);
        }
    }

    public void decide(long requester, long recipient, State state) {
        jdbc.update("""
                UPDATE social_friend_requests SET state = ?, decided_at = CURRENT_TIMESTAMP(6)
                WHERE low_id = ? AND high_id = ? AND requester_id = ? AND state = 'PENDING'
                """, state.name(), Math.min(requester, recipient), Math.max(requester, recipient), requester);
    }

    public void remove(long first, long second) {
        jdbc.update("DELETE FROM social_friend_requests WHERE low_id = ? AND high_id = ?",
                Math.min(first, second), Math.max(first, second));
    }

    public Page<Long> friends(long user, Pageable page) {
        Long count = jdbc.queryForObject("SELECT COUNT(DISTINCT peer_id) " + RELATIONSHIPS,
                Long.class, user, user, user, user, user);
        var ids = jdbc.queryForList("SELECT peer_id " + RELATIONSHIPS
                        + " GROUP BY peer_id ORDER BY MAX(connected_at) DESC, peer_id LIMIT ? OFFSET ?",
                Long.class, user, user, user, user, user, page.getPageSize(), page.getOffset());
        return new PageImpl<>(ids, page, count);
    }

    public Page<Long> incoming(long user, Pageable page) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) " + RECEIVED, Long.class, user, user, user, user, user);
        var ids = jdbc.queryForList("SELECT requester_id " + RECEIVED
                        + " ORDER BY created_at DESC, requester_id LIMIT ? OFFSET ?",
                Long.class, user, user, user, user, user, page.getPageSize(), page.getOffset());
        return new PageImpl<>(ids, page, count);
    }

    public enum State { PENDING, ACCEPTED, DECLINED }

    public record Request(long requesterId, State state) { }
}
