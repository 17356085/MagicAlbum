package com.example.demo.ai.summary.service;

import com.example.demo.ai.summary.repo.SummaryTaskRepository;
import com.example.demo.ai.summary.repo.SummaryAttemptRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Called with the parent content lock already held. Never acquires a user lock. */
@Service
public class SummaryContentLifecycle {
    private final SummaryTaskRepository tasks;
    private final SummaryCancellationRegistry cancellation;
    private final SummaryAttemptRepository attempts;

    public SummaryContentLifecycle(SummaryTaskRepository tasks, SummaryCancellationRegistry cancellation, SummaryAttemptRepository attempts) {
        this.tasks = tasks; this.cancellation = cancellation; this.attempts = attempts;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void invalidate(Long threadId, long version) {
        var ids = tasks.findObsoleteActiveIds(threadId, version);
        Instant now = Instant.now();
        tasks.supersede(threadId, version, now);
        ids.forEach(id -> attempts.closeActive(id, "SUPERSEDED", "CONTENT_CHANGED", now));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { ids.forEach(cancellation::cancel); }
        });
    }
}
