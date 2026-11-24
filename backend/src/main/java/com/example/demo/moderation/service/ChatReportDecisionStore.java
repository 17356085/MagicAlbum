package com.example.demo.moderation.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Durable coordination state for moderation commands sent to the remote chat
 * owner.  The decision history is kept in the core database; the active row
 * and message lock serialize competing reports without reaching into the chat
 * database.
 */
@Service
@ConditionalOnProperty(name = "app.chat.owner", havingValue = "remote")
public class ChatReportDecisionStore {
    private static final int MAX_CLAIM_LIMIT = 100;
    private static final long LEASE_SECONDS = 30;
    private static final long MAX_RETRY_BACKOFF_SECONDS = 60;
    private static final Set<String> FAILURE_CODES = Set.of("STALE", "TARGET_MISSING", "CONFLICT");

    private static final RowMapper<Decision> DECISION_ROW = (rs, row) -> new Decision(
            UUID.fromString(rs.getString("decision_id")),
            rs.getLong("report_id"),
            rs.getLong("message_id"),
            rs.getLong("reviewer_id"),
            rs.getBoolean("hidden"),
            rs.getString("reason"),
            rs.getLong("version"),
            rs.getInt("attempts"));

    private static final RowMapper<StoredDecision> STORED_DECISION_ROW = (rs, row) -> new StoredDecision(
            DECISION_ROW.mapRow(rs, row),
            rs.getString("status"),
            instant(rs, "lease_until"));

    private final JdbcTemplate jdbc;

    public ChatReportDecisionStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Returns the one active operation for a report, if it has not terminated. */
    @Transactional(readOnly = true)
    public Optional<Decision> pendingForReport(long reportId) {
        requirePositive(reportId, "reportId");
        return jdbc.query("""
                SELECT d.decision_id, d.report_id, d.message_id, d.reviewer_id,
                       d.hidden, d.reason, d.version, d.attempts
                  FROM chat_report_decision_active a
                  JOIN chat_report_decisions d ON d.decision_id = a.decision_id
                 WHERE a.report_id = ?
                   AND d.status IN ('PENDING', 'CLAIMED')
                 ORDER BY d.version DESC, d.created_at DESC
                 LIMIT 1
                """, DECISION_ROW, reportId).stream().findFirst();
    }

    /**
     * Enqueue is part of the caller's case/audit transaction.  A missing
     * transaction is deliberately rejected by Spring's MANDATORY propagation.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Decision enqueue(long reportId, long messageId, long reviewerId, boolean hidden, String reason) {
        requirePositive(reportId, "reportId");
        requirePositive(messageId, "messageId");
        requirePositive(reviewerId, "reviewerId");
        String normalizedReason = normalizeReason(reason);

        lockMessage(messageId);
        Optional<Decision> current = activeForMessage(messageId);
        if (current.isPresent()) {
            Decision existing = current.get();
            if (existing.reportId() == reportId
                    && existing.reviewerId() == reviewerId
                    && existing.hidden() == hidden
                    && existing.reason().equals(normalizedReason)) {
                return existing;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "已有待聊天服务确认的审核操作");
        }

        Long previousVersion = jdbc.queryForObject("""
                SELECT next_version
                  FROM chat_report_decision_locks
                 WHERE message_id = ?
                 FOR UPDATE
                """, Long.class, messageId);
        if (previousVersion == null || previousVersion == Long.MAX_VALUE) {
            throw new IllegalStateException("Chat moderation decision version exhausted");
        }
        long version = previousVersion + 1;
        jdbc.update("""
                UPDATE chat_report_decision_locks
                   SET next_version = ?, updated_at = ?
                 WHERE message_id = ?
                """, version, timestamp(Instant.now()), messageId);

        UUID decisionId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO chat_report_decisions
                    (decision_id, report_id, message_id, reviewer_id, hidden, reason,
                     version, status, attempts, available_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                """, decisionId.toString(), reportId, messageId, reviewerId, hidden,
                normalizedReason, version, timestamp(now), timestamp(now), timestamp(now));
        jdbc.update("""
                INSERT INTO chat_report_decision_active
                    (message_id, report_id, decision_id, version, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, messageId, reportId, decisionId.toString(), version, timestamp(now));
        return new Decision(decisionId, reportId, messageId, reviewerId, hidden,
                normalizedReason, version, 0);
    }

    /**
     * Claims a small batch in its own transaction.  The transaction commits
     * when this method returns, before the dispatcher performs a network call.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Decision> claim(int limit) {
        if (limit <= 0) return List.of();
        int actualLimit = Math.min(limit, MAX_CLAIM_LIMIT);
        Instant now = Instant.now();
        Timestamp nowTimestamp = timestamp(now);
        List<StoredDecision> candidates = jdbc.query("""
                SELECT d.decision_id, d.report_id, d.message_id, d.reviewer_id,
                       d.hidden, d.reason, d.version, d.attempts, d.status,
                       d.lease_until
                  FROM chat_report_decisions d
                 WHERE (d.status = 'PENDING' AND d.available_at <= ?)
                    OR (d.status = 'CLAIMED' AND d.lease_until IS NOT NULL
                        AND d.lease_until <= ?)
                 ORDER BY d.available_at, d.created_at, d.decision_id
                 LIMIT ? FOR UPDATE SKIP LOCKED
                """, STORED_DECISION_ROW, nowTimestamp, nowTimestamp, actualLimit);

        List<Decision> claimed = new ArrayList<>(candidates.size());
        Timestamp leaseUntil = timestamp(now.plusSeconds(LEASE_SECONDS));
        for (StoredDecision candidate : candidates) {
            int attempts = Math.addExact(candidate.decision().attempts(), 1);
            int changed = jdbc.update("""
                    UPDATE chat_report_decisions
                       SET status = 'CLAIMED', attempts = ?, lease_until = ?,
                           last_error_code = NULL, updated_at = ?
                     WHERE decision_id = ? AND attempts = ?
                       AND ((status = 'PENDING' AND available_at <= ?)
                         OR (status = 'CLAIMED' AND lease_until IS NOT NULL
                             AND lease_until <= ?))
                    """, attempts, leaseUntil, nowTimestamp,
                    candidate.decision().decisionId().toString(), candidate.decision().attempts(),
                    nowTimestamp, nowTimestamp);
            if (changed == 1) {
                Decision decision = candidate.decision();
                claimed.add(new Decision(decision.decisionId(), decision.reportId(), decision.messageId(),
                        decision.reviewerId(), decision.hidden(), decision.reason(), decision.version(), attempts));
            }
        }
        return claimed;
    }

    /**
     * Confirms only a currently leased decision.  The active pointer is
     * removed in the same transaction as the terminal history update.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean confirm(UUID decisionId) {
        if (decisionId == null) return false;
        StoredDecision snapshot = findDecision(decisionId, false).orElse(null);
        if (snapshot == null) return false;

        lockMessage(snapshot.decision().messageId());
        StoredDecision current = findDecision(decisionId, true).orElse(null);
        if (current == null || !"CLAIMED".equals(current.status())
                || !leased(current.leaseUntil(), Instant.now())) {
            return false;
        }

        Instant now = Instant.now();
        int changed = jdbc.update("""
                UPDATE chat_report_decisions
                   SET status = 'CONFIRMED', lease_until = NULL,
                       last_error_code = NULL, updated_at = ?
                 WHERE decision_id = ? AND status = 'CLAIMED' AND lease_until > ?
                """, timestamp(now), decisionId.toString(), timestamp(now));
        if (changed != 1) return false;
        jdbc.update("""
                DELETE FROM chat_report_decision_active
                 WHERE message_id = ? AND decision_id = ?
                """, current.decision().messageId(), decisionId.toString());
        return true;
    }

    /** Retries the claimed generation after a bounded, database-visible backoff. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retry(UUID decisionId, int attempts) {
        if (decisionId == null || attempts < 1) return;
        StoredDecision current = findDecision(decisionId, true).orElse(null);
        Instant now = Instant.now();
        if (current == null || !"CLAIMED".equals(current.status())
                || current.decision().attempts() != attempts
                || !leased(current.leaseUntil(), now)) {
            return;
        }
        long backoff = Math.min(MAX_RETRY_BACKOFF_SECONDS,
                1L << Math.min(6, Math.max(0, attempts)));
        jdbc.update("""
                UPDATE chat_report_decisions
                   SET status = 'PENDING', available_at = ?, lease_until = NULL,
                       updated_at = ?
                 WHERE decision_id = ? AND status = 'CLAIMED' AND attempts = ?
                   AND lease_until > ?
                """, timestamp(now.plusSeconds(backoff)), timestamp(now),
                decisionId.toString(), attempts, timestamp(now));
    }

    /**
     * Records only a machine-level permanent outcome.  No remote message body,
     * credential, or transport exception is ever written to the database.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID decisionId, int attempts, String errorCode) {
        if (decisionId == null || attempts < 1) return;
        if (!FAILURE_CODES.contains(errorCode)) {
            throw new IllegalArgumentException("Unsupported chat moderation error code");
        }
        StoredDecision snapshot = findDecision(decisionId, false).orElse(null);
        if (snapshot == null) return;

        lockMessage(snapshot.decision().messageId());
        StoredDecision current = findDecision(decisionId, true).orElse(null);
        Instant now = Instant.now();
        if (current == null || !"CLAIMED".equals(current.status())
                || current.decision().attempts() != attempts
                || !leased(current.leaseUntil(), now)) {
            return;
        }
        int changed = jdbc.update("""
                UPDATE chat_report_decisions
                   SET status = 'FAILED', lease_until = NULL,
                       last_error_code = ?, updated_at = ?
                 WHERE decision_id = ? AND status = 'CLAIMED' AND attempts = ?
                   AND lease_until > ?
                """, errorCode, timestamp(now), decisionId.toString(), attempts, timestamp(now));
        if (changed == 1) {
            jdbc.update("""
                    DELETE FROM chat_report_decision_active
                     WHERE message_id = ? AND decision_id = ?
                    """, current.decision().messageId(), decisionId.toString());
        }
    }

    private Optional<Decision> activeForMessage(long messageId) {
        return jdbc.query("""
                SELECT d.decision_id, d.report_id, d.message_id, d.reviewer_id,
                       d.hidden, d.reason, d.version, d.attempts
                  FROM chat_report_decision_active a
                  JOIN chat_report_decisions d ON d.decision_id = a.decision_id
                 WHERE a.message_id = ?
                   AND d.status IN ('PENDING', 'CLAIMED')
                 ORDER BY d.version DESC
                 LIMIT 1 FOR UPDATE
                """, DECISION_ROW, messageId).stream().findFirst();
    }

    private Optional<StoredDecision> findDecision(UUID decisionId, boolean forUpdate) {
        String lock = forUpdate ? " FOR UPDATE" : "";
        return jdbc.query("""
                SELECT decision_id, report_id, message_id, reviewer_id,
                       hidden, reason, version, attempts, status, lease_until
                  FROM chat_report_decisions
                 WHERE decision_id = ?
                """ + lock, STORED_DECISION_ROW, decisionId.toString()).stream().findFirst();
    }

    /** The no-op upsert obtains the message's InnoDB row lock for this transaction. */
    private void lockMessage(long messageId) {
        jdbc.update("""
                INSERT INTO chat_report_decision_locks (message_id, next_version)
                VALUES (?, 0)
                ON DUPLICATE KEY UPDATE message_id = VALUES(message_id)
                """, messageId);
    }

    private static boolean leased(Instant leaseUntil, Instant now) {
        return leaseUntil != null && leaseUntil.isAfter(now);
    }

    private static String normalizeReason(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty() || normalized.length() > 500) {
            throw new IllegalArgumentException("Chat moderation reason must be between 1 and 500 characters");
        }
        return normalized;
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record StoredDecision(Decision decision, String status, Instant leaseUntil) {}

    public record Decision(UUID decisionId, long reportId, long messageId, long reviewerId,
                           boolean hidden, String reason, long version, int attempts) {}
}
