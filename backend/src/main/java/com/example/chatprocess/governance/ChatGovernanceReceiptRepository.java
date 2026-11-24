package com.example.chatprocess.governance;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** JDBC-only receipt access for the chat-owned moderation ledger. */
@Repository
public class ChatGovernanceReceiptRepository {
    private final JdbcTemplate jdbc;

    public ChatGovernanceReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Receipt> findDecisionForUpdate(UUID decisionId) {
        return jdbc.query("""
                        SELECT decision_id, message_id, report_id, decision_version, hidden, outcome
                        FROM chat_moderation_receipts
                        WHERE decision_id = ?
                        FOR UPDATE
                        """, ChatGovernanceReceiptRepository::map, decisionId.toString()).stream().findFirst();
    }

    public Optional<Receipt> findDecision(UUID decisionId) {
        return jdbc.query("""
                        SELECT decision_id, message_id, report_id, decision_version, hidden, outcome
                        FROM chat_moderation_receipts
                        WHERE decision_id = ?
                        """, ChatGovernanceReceiptRepository::map, decisionId.toString()).stream().findFirst();
    }

    public Optional<Receipt> findVersionForUpdate(long messageId, long version) {
        return jdbc.query("""
                        SELECT decision_id, message_id, report_id, decision_version, hidden, outcome
                        FROM chat_moderation_receipts
                        WHERE message_id = ? AND decision_version = ?
                        FOR UPDATE
                        """, ChatGovernanceReceiptRepository::map, messageId, version).stream().findFirst();
    }

    public Optional<Long> latestVersion(long messageId) {
        return jdbc.query("""
                        SELECT decision_version
                        FROM chat_moderation_receipts
                        WHERE message_id = ?
                        ORDER BY decision_version DESC
                        LIMIT 1
                        """, (rs, row) -> rs.getLong(1), messageId).stream().findFirst();
    }

    public void insert(UUID decisionId, long messageId, long reportId, long version,
                       boolean hidden, ChatGovernanceService.Outcome outcome) throws DuplicateKeyException {
        jdbc.update("""
                        INSERT INTO chat_moderation_receipts
                            (decision_id, message_id, report_id, decision_version, hidden, outcome)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, decisionId.toString(), messageId, reportId, version, hidden, outcome.name());
    }

    private static Receipt map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Receipt(UUID.fromString(rs.getString("decision_id")), rs.getLong("message_id"),
                rs.getLong("report_id"), rs.getLong("decision_version"), rs.getBoolean("hidden"),
                ChatGovernanceService.Outcome.valueOf(rs.getString("outcome")));
    }

    public record Receipt(UUID decisionId, long messageId, long reportId, long version,
                          boolean hidden, ChatGovernanceService.Outcome outcome) {
        boolean matches(UUID decision, long message, long report, long incomingVersion, boolean incomingHidden) {
            return decisionId.equals(decision) && messageId == message && reportId == report
                    && version == incomingVersion && hidden == incomingHidden;
        }
    }
}
