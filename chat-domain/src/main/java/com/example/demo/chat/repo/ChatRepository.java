package com.example.demo.chat.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ChatRepository {
    private static final String MESSAGE_SELECT = """
            SELECT msg.* FROM chat_messages msg WHERE msg.conversation_id = ?
            """;
    private static final RowMapper<Room> ROOM = (rs, row) -> new Room(rs.getLong("id"), nullableId(rs, "direct_low_id"),
            nullableId(rs, "direct_high_id"), nullableId(rs, "requester_id"), nullableId(rs, "owner_id"),
            rs.getString("state"), rs.getLong("last_seq"), rs.getLong("history_revision"));
    private static final RowMapper<MessageRow> MESSAGE = (rs, row) -> new MessageRow(rs.getLong("id"), rs.getLong("seq"),
            rs.getLong("sender_id"), rs.getBoolean("hidden") || rs.getTimestamp("recalled_at") != null
                    ? null : rs.getString("body"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("recalled_at") != null);

    private final JdbcTemplate jdbc;

    public ChatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Room> direct(long a, long b) {
        return jdbc.query("SELECT * FROM chat_conversations WHERE direct_low_id = ? AND direct_high_id = ?",
                ROOM, Math.min(a, b), Math.max(a, b)).stream().findFirst();
    }

    public Optional<Room> room(long id, boolean forUpdate) {
        return jdbc.query("SELECT * FROM chat_conversations WHERE id = ?" + (forUpdate ? " FOR UPDATE" : ""),
                ROOM, id).stream().findFirst();
    }

    public long createDirect(long user, long peer, String state) {
        long id = insertId("""
                INSERT INTO chat_conversations (direct_low_id, direct_high_id, requester_id, state)
                VALUES (?, ?, ?, ?)
                """, Math.min(user, peer), Math.max(user, peer), user, state);
        jdbc.update("INSERT INTO chat_members (conversation_id, user_id, state) VALUES (?, ?, 'JOINED'), (?, ?, 'JOINED')",
                id, user, id, peer);
        return id;
    }

    public long createGroup(long owner, String title) {
        long id = insertId("INSERT INTO chat_conversations (owner_id, title, state) VALUES (?, ?, 'ACTIVE')", owner, title);
        jdbc.update("INSERT INTO chat_members (conversation_id, user_id, state) VALUES (?, ?, 'JOINED')", id, owner);
        return id;
    }

    public void updateState(long id, String state) {
        jdbc.update("UPDATE chat_conversations SET state = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?", state, id);
    }

    public void transfer(long id, long owner) {
        jdbc.update("UPDATE chat_conversations SET owner_id = ? WHERE id = ?", owner, id);
    }

    public Page<InboxRow> inbox(long user, boolean requests, PageRequest page, Instant invitationCutoff) {
        String source = """
                FROM chat_members cm JOIN chat_conversations c ON c.id = cm.conversation_id
                WHERE cm.user_id = ? AND c.state <> 'CLOSED' AND
                """ + (requests ? """
                ((cm.state = 'INVITED' AND cm.invited_at > ?) OR
                 (cm.state = 'JOINED' AND c.state = 'PENDING' AND c.requester_id <> cm.user_id))
                """ : """
                cm.state = 'JOINED' AND (c.state <> 'PENDING' OR c.requester_id = cm.user_id)
                """);
        Object[] bindings = requests ? new Object[] {user, Timestamp.from(invitationCutoff)} : new Object[] {user};
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + source, Long.class, bindings);
        String select = """
                SELECT inbox.*,
                   (SELECT CASE WHEN msg.recalled_at IS NOT NULL THEN '消息已撤回' ELSE msg.body END
                    FROM chat_messages msg WHERE msg.conversation_id = inbox.id
                     AND inbox.membership = 'JOINED' AND msg.seq >= inbox.visible_from_seq AND msg.hidden = FALSE
                     ORDER BY msg.seq DESC LIMIT 1) AS preview,
                   EXISTS (SELECT 1 FROM chat_messages msg WHERE msg.conversation_id = inbox.id
                     AND inbox.membership = 'JOINED' AND msg.seq > inbox.last_read_seq AND msg.seq >= inbox.visible_from_seq
                     AND msg.sender_id <> inbox.user_id AND msg.hidden = FALSE AND msg.recalled_at IS NULL) AS unread
                FROM (SELECT c.*, cm.user_id, cm.state AS membership, cm.visible_from_seq,
                             cm.last_read_seq, COALESCE(
                               CASE WHEN cm.state = 'INVITED' THEN cm.invited_at END, c.updated_at
                             ) AS activity
                """ + " " + source + """
                ORDER BY activity DESC, c.id DESC LIMIT ? OFFSET ?) inbox
                ORDER BY inbox.activity DESC, inbox.id DESC
                """;
        Object[] args = java.util.Arrays.copyOf(bindings, bindings.length + 2);
        args[bindings.length] = page.getPageSize();
        args[bindings.length + 1] = page.getOffset();
        List<InboxRow> items = jdbc.query(select, (rs, row) -> {
            Long peer = nullableId(rs, "direct_low_id");
            if (peer != null && peer == user) peer = nullableId(rs, "direct_high_id");
            return new InboxRow(rs.getLong("id"), peer, rs.getString("title"),
                    rs.getString("state"), nullableId(rs, "owner_id"), nullableId(rs, "requester_id"),
                    rs.getString("membership"), rs.getString("preview"), rs.getBoolean("unread"),
                    rs.getTimestamp("activity").toInstant());
        }, args);
        return new PageImpl<>(items, page, total == null ? 0 : total);
    }

    public Optional<Membership> membership(long id, long user) {
        return jdbc.query("""
                SELECT state, invited_at, visible_from_seq FROM chat_members WHERE conversation_id = ? AND user_id = ?
                """, (rs, row) -> new Membership(rs.getString("state"),
                rs.getTimestamp("invited_at") == null ? null : rs.getTimestamp("invited_at").toInstant(),
                rs.getLong("visible_from_seq")), id, user).stream().findFirst();
    }

    public List<MemberRow> members(long id, Instant cutoff) {
        return jdbc.query("""
                SELECT m.user_id, m.state FROM chat_members m
                WHERE m.conversation_id = ? AND (m.state = 'JOINED' OR (m.state = 'INVITED' AND m.invited_at > ?))
                ORDER BY m.state DESC, m.user_id
                """, (rs, row) -> new MemberRow(rs.getLong("user_id"), rs.getString("state")), id, Timestamp.from(cutoff));
    }

    public int joinedCount(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM chat_members WHERE conversation_id = ? AND state = 'JOINED'",
                Integer.class, id);
    }

    public List<Long> joinedUserIds(long id) {
        return jdbc.queryForList("SELECT user_id FROM chat_members WHERE conversation_id = ? AND state = 'JOINED'",
                Long.class, id);
    }

    public void invite(long id, long user, Instant now) {
        if (jdbc.update("""
                UPDATE chat_members SET state = 'INVITED', invited_at = ?
                WHERE conversation_id = ? AND user_id = ?
                """, Timestamp.from(now), id, user) == 0) {
            jdbc.update("INSERT INTO chat_members (conversation_id, user_id, state, invited_at) VALUES (?, ?, 'INVITED', ?)",
                    id, user, Timestamp.from(now));
        }
    }

    public void join(long id, long user, long previousSeq) {
        jdbc.update("""
                UPDATE chat_members SET state = 'JOINED', invited_at = NULL, visible_from_seq = ?, last_read_seq = ?
                WHERE conversation_id = ? AND user_id = ?
                """, previousSeq + 1, previousSeq, id, user);
    }

    public void leave(long id, long user) {
        jdbc.update("UPDATE chat_members SET state = 'LEFT' WHERE conversation_id = ? AND user_id = ?", id, user);
    }

    public void read(long id, long user, long seq) {
        jdbc.update("""
                UPDATE chat_members SET last_read_seq = ?
                WHERE conversation_id = ? AND user_id = ? AND last_read_seq < ?
                """, seq, id, user, seq);
    }

    public List<MessageRow> history(long id, long floor, Long before, boolean forward, int limit) {
        String order = forward ? " ORDER BY msg.seq ASC LIMIT ?" : " ORDER BY msg.seq DESC LIMIT ?";
        String sql = MESSAGE_SELECT + " AND msg.seq >= ?" + (before == null ? "" : " AND msg.seq < ?") + order;
        return before == null ? jdbc.query(sql, MESSAGE, id, floor, limit) : jdbc.query(sql, MESSAGE, id, floor, before, limit);
    }

    public Optional<StoredMessage> previous(long id, long sender, UUID key) {
        return jdbc.query(MESSAGE_SELECT + " AND msg.sender_id = ? AND msg.client_message_id = ?",
                (rs, row) -> new StoredMessage(rs.getString("body"), MESSAGE.mapRow(rs, row)),
                id, sender, key.toString()).stream().findFirst();
    }

    public boolean canReport(long user, long message) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM chat_messages msg
                  JOIN chat_conversations c ON c.id = msg.conversation_id AND c.state <> 'CLOSED'
                  JOIN chat_members m ON m.conversation_id = c.id AND m.user_id = ? AND m.state = 'JOINED'
                  WHERE msg.id = ? AND msg.seq >= m.visible_from_seq AND msg.sender_id <> m.user_id)
                """, Boolean.class, user, message));
    }

    public Optional<String> reportEvidence(long message) {
        return jdbc.query("SELECT body FROM chat_messages WHERE id = ?",
                (rs, row) -> rs.getString("body"), message).stream().findFirst();
    }

    public Optional<Long> messageRoom(long message) {
        return jdbc.queryForList("SELECT conversation_id FROM chat_messages WHERE id = ?", Long.class, message)
                .stream().findFirst();
    }

    public boolean setHidden(long message, boolean hidden) {
        return jdbc.update("UPDATE chat_messages SET hidden = ? WHERE id = ? AND hidden <> ?", hidden, message, hidden) > 0;
    }

    public Optional<MessageRow> recallTarget(long room, long message, long sender, long visibleFromSeq) {
        return jdbc.query(MESSAGE_SELECT + " AND msg.id = ? AND msg.sender_id = ? AND msg.seq >= ?",
                MESSAGE, room, message, sender, visibleFromSeq).stream().findFirst();
    }

    public boolean recallRecent(long room, long message, long sender) {
        return jdbc.update("""
                UPDATE chat_messages SET recalled_at = CURRENT_TIMESTAMP(6)
                WHERE conversation_id = ? AND id = ? AND sender_id = ? AND recalled_at IS NULL
                  AND created_at >= TIMESTAMPADD(SECOND, -120, CURRENT_TIMESTAMP(6))
                """, room, message, sender) == 1;
    }

    public void advanceHistoryRevision(long room) {
        jdbc.update("UPDATE chat_conversations SET history_revision = history_revision + 1 WHERE id = ?", room);
    }

    public MessageRow write(Room room, long sender, UUID key, String body) {
        long seq = room.lastSeq() + 1;
        jdbc.update("""
                INSERT INTO chat_messages (conversation_id, seq, sender_id, client_message_id, body)
                VALUES (?, ?, ?, ?, ?)
                """, room.id(), seq, sender, key.toString(), body);
        jdbc.update("UPDATE chat_conversations SET last_seq = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?", seq, room.id());
        return jdbc.queryForObject(MESSAGE_SELECT + " AND msg.seq = ?", MESSAGE, room.id(), seq);
    }

    public int used(long user, LocalDate date) {
        return jdbc.query("SELECT used_count FROM chat_daily_usage WHERE user_id = ? AND quota_date = ?",
                (rs, row) -> rs.getInt(1), user, date).stream().findFirst().orElse(0);
    }

    public boolean takeQuota(long user, LocalDate date, int limit) {
        jdbc.update("""
                INSERT INTO chat_daily_usage (user_id, quota_date) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE used_count = used_count
                """, user, date);
        return jdbc.update("""
                UPDATE chat_daily_usage SET used_count = used_count + 1
                WHERE user_id = ? AND quota_date = ? AND used_count < ?
                """, user, date, limit) == 1;
    }

    public boolean allowsRequests(long user, boolean fallback) {
        return jdbc.query("SELECT allow_requests FROM chat_preferences WHERE user_id = ?",
                (rs, row) -> rs.getBoolean(1), user).stream().findFirst().orElse(fallback);
    }

    public void preferences(long user, boolean allow) {
        jdbc.update("""
                INSERT INTO chat_preferences (user_id, allow_requests) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE allow_requests = ?
                """, user, allow, allow);
    }

    private long insertId(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, new String[] {"id"});
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        Number id = key.getKey();
        if (id == null) throw new DataRetrievalFailureException("Chat conversation insert did not return an ID");
        return id.longValue();
    }

    private static Long nullableId(ResultSet rs, String key) throws SQLException {
        long id = rs.getLong(key);
        return rs.wasNull() ? null : id;
    }

    public record Room(long id, Long low, Long high, Long requester, Long owner, String state, long lastSeq,
                       long historyRevision) {
        public boolean direct() { return low != null; }
        public boolean hasPeer(long user) { return direct() && (low == user || high == user); }
        public long peer(long user) { return low == user ? high : low; }
    }

    public record Membership(String state, Instant invitedAt, long visibleFromSeq) {
        public boolean joined() { return state.equals("JOINED"); }
        public boolean invitedAfter(Instant cutoff) {
            return state.equals("INVITED") && invitedAt != null && invitedAt.isAfter(cutoff);
        }
    }

    public record InboxRow(long id, Long peerId, String title, String state, Long ownerId, Long requesterId,
                           String membership, String preview, boolean unread, Instant updatedAt) {}

    public record MemberRow(long userId, String state) {}

    public record MessageRow(long id, long seq, long senderId, String content, Instant createdAt, boolean recalled) {}

    public record StoredMessage(String body, MessageRow message) {}
}
