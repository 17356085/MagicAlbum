package com.example.demo.ai.summary.service;

import com.example.demo.ai.summary.repo.SummaryTaskRepository;
import java.time.Instant;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final Clock clock;
    private String after = "";
    private Instant scanAt;
    @Autowired
    public SummaryTaskRecovery(SummaryTaskRepository tasks, SummaryTaskPublisher publisher,
                               @org.springframework.beans.factory.annotation.Value("${app.ai.summary.recovery-enabled:true}") boolean enabled) {
        this(tasks, publisher, enabled, Clock.systemUTC());
    }

    public SummaryTaskRecovery(SummaryTaskRepository tasks, SummaryTaskPublisher publisher, boolean enabled, Clock clock) {
        this.tasks = tasks; this.publisher = publisher;
        this.enabled = enabled;
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    @Scheduled(initialDelayString = "${app.ai.summary.recovery-interval-ms:30000}",
            fixedDelayString = "${app.ai.summary.recovery-interval-ms:30000}")
    public synchronized void recover() {
        if (!enabled) return;
        // Freeze eligibility until the finite scan finishes. Newly eligible low
        // UUIDs join the next scan instead of continuously extending this one.
        if (scanAt == null) scanAt = clock.instant();
        var ids = tasks.recoverable(after, scanAt, scanAt.minusSeconds(30), PageRequest.of(0, 20));
        for (String id : ids) {
            if (!publisher.publish(id)) return;
            after = id;
        }
        if (ids.size() < 20) { after = ""; scanAt = null; }
    }
}
