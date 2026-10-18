package com.example.demo.threads.service;

import com.example.demo.ai.service.AiService;
import com.example.demo.ai.service.AiSummaryCacheService;
import com.example.demo.common.config.RabbitMQConfig;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@ConditionalOnProperty(name = "app.rabbit.enabled", havingValue = "true")
public class ThreadSummaryListener {

    private static final Logger log = LoggerFactory.getLogger(ThreadSummaryListener.class);

    private final ThreadRepository threadRepository;
    private final AiService aiService;
    private final AiSummaryCacheService aiSummaryCacheService;

    public ThreadSummaryListener(ThreadRepository threadRepository, AiService aiService, AiSummaryCacheService aiSummaryCacheService) {
        this.threadRepository = threadRepository;
        this.aiService = aiService;
        this.aiSummaryCacheService = aiSummaryCacheService;
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_THREAD_SUMMARY)
    public void processThreadSummary(Long threadId) {
        log.info("Processing summary for thread: {}", threadId);
        if (threadId == null) {
            return;
        }
        Optional<Thread> threadOpt = threadRepository.findById(threadId);
        if (threadOpt.isEmpty()) {
            log.warn("Thread not found: {}", threadId);
            aiSummaryCacheService.evict(threadId);
            return;
        }

        Thread thread = threadOpt.get();
        if (!isNormal(thread)) {
            aiSummaryCacheService.evict(threadId);
            return;
        }
        if ("COMPLETED".equals(thread.getSummaryStatus())) {
            log.info("Summary already exists for thread: {}", threadId);
            return;
        }

        String owner = java.util.UUID.randomUUID().toString();
        java.time.Instant now = java.time.Instant.now();
        // AI requests are bounded to 120 seconds; the lease includes a full minute of scheduling margin.
        if (threadRepository.claimSummaryProcessingIfNormal(threadId, owner, now, now.plusSeconds(180)) == 0) {
            threadRepository.exhaustSummaryLease(threadId, now);
            aiSummaryCacheService.evict(threadId);
            return;
        }

        Optional<Thread> processingThreadOpt = threadRepository.findById(threadId);
        if (processingThreadOpt.isEmpty() || !isNormal(processingThreadOpt.get())) {
            aiSummaryCacheService.evict(threadId);
            return;
        }
        Thread processingThread = processingThreadOpt.get();

        try {
            if (!isNormal(threadId)) {
                aiSummaryCacheService.evict(threadId);
                return;
            }
            aiSummaryCacheService.put(threadId, processingThread.getSummary(), "PROCESSING");

            String summary = aiService.generateSummary(processingThread.getContentMd());
            if (summary == null || summary.isBlank()) {
                throw new IllegalStateException("AI returned no summary content");
            }
            if (!isNormal(threadId)) {
                aiSummaryCacheService.evict(threadId);
                return;
            }
            if (threadRepository.completeSummaryIfNormal(threadId, summary, owner,
                    java.time.Instant.now(), processingThread.getUpdatedAt()) == 0) {
                threadRepository.markSummaryFailedIfProcessingAndNormal(threadId, owner);
                aiSummaryCacheService.evict(threadId);
                return;
            }
            if (isNormal(threadId)) {
                aiSummaryCacheService.put(threadId, summary, "COMPLETED");
            } else {
                aiSummaryCacheService.evict(threadId);
            }
            log.info("Summary generated for thread: {}", threadId);
        } catch (Exception e) {
            log.error("Failed to generate summary for thread: " + threadId, e);
            if (threadRepository.markSummaryFailedIfProcessingAndNormal(threadId, owner) > 0 && isNormal(threadId)) {
                aiSummaryCacheService.put(threadId, processingThread.getSummary(), "FAILED");
            } else {
                aiSummaryCacheService.evict(threadId);
            }
        }
    }

    private boolean isNormal(Long threadId) {
        return threadRepository.findById(threadId).map(this::isNormal).orElse(false);
    }

    private boolean isNormal(Thread thread) {
        return thread != null && "NORMAL".equals(thread.getStatus());
    }
}
