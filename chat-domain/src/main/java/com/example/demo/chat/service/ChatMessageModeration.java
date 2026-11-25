package com.example.demo.chat.service;

import com.example.demo.chat.repo.ChatRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Access to the single message identified by a governance case. */
@Service
public class ChatMessageModeration {
    private final ChatRepository repository;
    private final ChatChangePublisher realtime;
    private final boolean localOwner;

    public ChatMessageModeration(ChatRepository repository, ChatChangePublisher realtime,
                                 @Value("${app.chat.owner:local}") String owner) {
        this.repository = repository;
        this.realtime = realtime;
        if (!"local".equals(owner) && !"remote".equals(owner)) {
            throw new IllegalStateException("app.chat.owner must be local or remote");
        }
        this.localOwner = "local".equals(owner);
    }

    @Transactional(readOnly = true)
    public void requireReportable(long reporterId, long messageId) {
        requireLocalOwner();
        if (!repository.canReport(reporterId, messageId)) throw missing();
    }

    @Transactional(readOnly = true)
    public String reportEvidence(long messageId) {
        requireLocalOwner();
        return repository.reportEvidence(messageId).orElse(null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void setMessageHidden(long messageId, boolean hidden) {
        requireLocalOwner();
        long conversationId = repository.messageRoom(messageId).orElseThrow(ChatMessageModeration::missing);
        repository.room(conversationId, true).orElseThrow(ChatMessageModeration::missing);
        if (repository.setHidden(messageId, hidden)) {
            repository.advanceHistoryRevision(conversationId);
            realtime.changed(() -> repository.joinedUserIds(conversationId));
        }
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "无法访问该用户或会话");
    }

    private void requireLocalOwner() {
        if (!localOwner) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "聊天举报暂不可用");
    }
}
