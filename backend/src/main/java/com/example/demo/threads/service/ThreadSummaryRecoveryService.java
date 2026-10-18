package com.example.demo.threads.service;

import com.example.demo.common.config.RabbitMQConfig;
import com.example.demo.threads.repo.ThreadSummaryRecoveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** PENDING is the durable intent, not an acknowledgement of broker delivery. */
@Service
@ConditionalOnProperty(name = "app.rabbit.enabled", havingValue = "true")
public class ThreadSummaryRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(ThreadSummaryRecoveryService.class);

    private final ThreadSummaryRecoveryRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final Clock clock;
    private final int batchSize;
    private final Duration minimumAge;
    private long afterId;

    @Autowired
    public ThreadSummaryRecoveryService(ThreadSummaryRecoveryRepository repository,
                                        RabbitTemplate rabbitTemplate,
                                        @Value("${app.ai.summary.recovery-batch-size:20}") int batchSize,
                                        @Value("${app.ai.summary.recovery-min-age-seconds:60}") long minimumAgeSeconds) {
        this(repository, rabbitTemplate, Clock.systemUTC(), batchSize, Duration.ofSeconds(minimumAgeSeconds));
    }

    ThreadSummaryRecoveryService(ThreadSummaryRecoveryRepository repository, RabbitTemplate rabbitTemplate,
                                 Clock clock, int batchSize, Duration minimumAge) {
        if (minimumAge == null || minimumAge.compareTo(Duration.ofSeconds(30)) < 0) {
            throw new IllegalArgumentException("Summary recovery minimum age must be at least 30 seconds");
        }
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.clock = clock;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
        this.minimumAge = minimumAge;
    }

    public synchronized void recoverPending() {
        List<Long> ids = repository.findPendingIds(afterId, clock.instant().minus(minimumAge), clock.instant(),
                PageRequest.of(0, batchSize));
        // Walk the full key range so one failing/slow item cannot monopolize a batch.
        // The cursor is only an optimization: restart begins another pass from zero.
        afterId = ids.size() < batchSize ? 0 : ids.get(ids.size() - 1);
        for (Long threadId : ids) {
            try {
                rabbitTemplate.convertAndSend(RabbitMQConfig.QUEUE_THREAD_SUMMARY, threadId);
            } catch (RuntimeException failure) {
                // Leave PENDING in the database for a subsequent pass, including after restart.
                log.warn("Summary recovery publish failed thread={} error={}", threadId,
                        failure.getClass().getSimpleName());
            }
        }
    }
}
