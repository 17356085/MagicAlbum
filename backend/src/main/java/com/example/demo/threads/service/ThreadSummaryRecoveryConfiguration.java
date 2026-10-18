package com.example.demo.threads.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.rabbit.enabled", havingValue = "true")
public class ThreadSummaryRecoveryConfiguration {
    @Bean
    @ConditionalOnProperty(name = "app.ai.summary.recovery-enabled", havingValue = "true", matchIfMissing = true)
    SummaryRecoveryPoller summaryRecoveryPoller(ThreadSummaryRecoveryService service) {
        return new SummaryRecoveryPoller(service);
    }

    static final class SummaryRecoveryPoller {
        private final ThreadSummaryRecoveryService service;

        SummaryRecoveryPoller(ThreadSummaryRecoveryService service) {
            this.service = service;
        }

        @Scheduled(initialDelayString = "${app.ai.summary.recovery-interval-ms:60000}",
                fixedDelayString = "${app.ai.summary.recovery-interval-ms:60000}")
        public void poll() {
            service.recoverPending();
        }
    }
}
