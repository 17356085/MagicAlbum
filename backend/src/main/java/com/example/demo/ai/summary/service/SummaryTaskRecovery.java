package com.example.demo.ai.summary.service;

import com.example.demo.ai.summary.repo.SummaryTaskRepository;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.rabbit.enabled", havingValue = "true")
public class SummaryTaskRecovery {
    private final SummaryTaskRepository tasks;
    private final SummaryTaskPublisher publisher;
    private final boolean enabled;
    private String after = "";
    public SummaryTaskRecovery(SummaryTaskRepository tasks, SummaryTaskPublisher publisher,
                               @org.springframework.beans.factory.annotation.Value("${app.ai.summary.recovery-enabled:true}") boolean enabled) {
        this.tasks = tasks; this.publisher = publisher;
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "${app.ai.summary.recovery-interval-ms:30000}",
            fixedDelayString = "${app.ai.summary.recovery-interval-ms:30000}")
    public synchronized void recover() {
        if (!enabled) return;
        Instant now = Instant.now();
        var ids = tasks.recoverable(after, now, now.minusSeconds(30), PageRequest.of(0, 20));
        for (String id : ids) {
            if (!publisher.publish(id)) return;
            after = id;
        }
        if (ids.size() < 20) after = "";
    }
}
