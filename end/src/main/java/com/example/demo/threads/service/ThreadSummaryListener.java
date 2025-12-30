package com.example.demo.threads.service;

import com.example.demo.ai.service.AiService;
import com.example.demo.common.config.RabbitMQConfig;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class ThreadSummaryListener {

    private static final Logger log = LoggerFactory.getLogger(ThreadSummaryListener.class);

    private final ThreadRepository threadRepository;
    private final AiService aiService;

    public ThreadSummaryListener(ThreadRepository threadRepository, AiService aiService) {
        this.threadRepository = threadRepository;
        this.aiService = aiService;
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_THREAD_SUMMARY)
    public void processThreadSummary(Long threadId) {
        log.info("Processing summary for thread: {}", threadId);
        Optional<Thread> threadOpt = threadRepository.findById(threadId);
        if (threadOpt.isEmpty()) {
            log.warn("Thread not found: {}", threadId);
            return;
        }

        Thread thread = threadOpt.get();
        if ("COMPLETED".equals(thread.getSummaryStatus())) {
            log.info("Summary already exists for thread: {}", threadId);
            return;
        }

        try {
            // Update status to processing
            thread.setSummaryStatus("PROCESSING");
            threadRepository.save(thread);

            String summary = aiService.generateSummary(thread.getContentMd());
            
            thread.setSummary(summary);
            thread.setSummaryStatus("COMPLETED");
            threadRepository.save(thread);
            log.info("Summary generated for thread: {}", threadId);
        } catch (Exception e) {
            log.error("Failed to generate summary for thread: " + threadId, e);
            thread.setSummaryStatus("FAILED");
            threadRepository.save(thread);
        }
    }
}
