package com.example.chatprocess.governance;

import com.example.demo.chat.repo.ChatRepository;
import com.example.demo.chat.service.ChatChangePublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Receives core moderation commands in the chat owner process.
 *
 * <p>Conversation locking serializes all visibility decisions for a message.
 * The receipt, visibility change, history revision and after-commit realtime
 * hint therefore share one JDBC transaction.</p>
 */
@Service
public class ChatGovernanceService {
    private final ChatRepository chat;
    private final ChatGovernanceReceiptRepository receipts;
    private final ChatChangePublisher realtime;

    public ChatGovernanceService(ChatRepository chat,
                                 ChatGovernanceReceiptRepository receipts,
                                 ChatChangePublisher realtime) {
        this.chat = chat;
        this.receipts = receipts;
        this.realtime = realtime;
    }

    @Transactional(readOnly = true)
    public void requireReportable(long messageId, long reporterId) {
        if (!chat.canReport(reporterId, messageId)) throw missing();
    }

    @Transactional(readOnly = true)
    public String reportEvidence(long messageId) {
        return chat.reportEvidence(messageId).orElseThrow(ChatGovernanceService::missing);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt apply(UUID decisionId, long version, long messageId, long reportId, boolean hidden) {
        // This is only a fast replay check. The owning conversation is locked
        // before the locking receipt lookup below, avoiding cross-request gap
        // locks on the decision primary key.
        ChatGovernanceReceiptRepository.Receipt existing = receipts.findDecision(decisionId).orElse(null);
        if (existing != null) return existingOrConflict(existing, decisionId, version, messageId, reportId, hidden);

        long conversationId = chat.messageRoom(messageId).orElseThrow(ChatGovernanceService::missing);
        chat.room(conversationId, true).orElseThrow(ChatGovernanceService::missing);

        // A competing delivery may have committed while this request waited for
        // the conversation lock. Locking read makes the replay visible.
        existing = receipts.findDecisionForUpdate(decisionId).orElse(null);
        if (existing != null) return existingOrConflict(existing, decisionId, version, messageId, reportId, hidden);

        // A version can only be owned by one decision. Even an identical tuple
        // with a different UUID is a conflict rather than a second receipt.
        if (receipts.findVersionForUpdate(messageId, version).isPresent()) {
            throw versionConflict();
        }

        long latest = receipts.latestVersion(messageId).orElse(0L);
        Outcome outcome = version > latest ? Outcome.APPLIED : Outcome.STALE;
        try {
            receipts.insert(decisionId, messageId, reportId, version, hidden, outcome);
        } catch (DuplicateKeyException duplicate) {
            // A same UUID can arrive through another conversation concurrently.
            // Resolve that race to the durable receipt instead of mutating twice.
            ChatGovernanceReceiptRepository.Receipt raced = receipts.findDecisionForUpdate(decisionId).orElse(null);
            if (raced != null) return existingOrConflict(raced, decisionId, version, messageId, reportId, hidden);
            throw versionConflict();
        }
        if (outcome == Outcome.APPLIED) {
            boolean changed = chat.setHidden(messageId, hidden);
            if (changed) {
                chat.advanceHistoryRevision(conversationId);
                realtime.changed(() -> chat.joinedUserIds(conversationId));
            }
        }
        return new Receipt(decisionId, version, messageId, reportId, hidden, outcome);
    }

    private Receipt existingOrConflict(ChatGovernanceReceiptRepository.Receipt existing,
                                       UUID decisionId, long version, long messageId,
                                       long reportId, boolean hidden) {
        if (!existing.matches(decisionId, messageId, reportId, version, hidden)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "审核决定编号已绑定其他目标");
        }
        return new Receipt(existing.decisionId(), existing.version(), existing.messageId(), existing.reportId(),
                existing.hidden(), existing.outcome());
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "聊天消息不存在");
    }

    private static ResponseStatusException versionConflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "审核决定版本已绑定其他目标");
    }

    public enum Outcome { APPLIED, STALE }

    public record Receipt(UUID decisionId, long version, long messageId, long reportId,
                          boolean hidden, Outcome outcome) {}
}
