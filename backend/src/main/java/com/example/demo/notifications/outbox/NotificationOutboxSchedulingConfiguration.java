package com.example.demo.notifications.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@PropertySource("classpath:notification-outbox.properties")
@ConditionalOnProperty(name = "app.notifications.client.mode", havingValue = "remote", matchIfMissing = true)
public class NotificationOutboxSchedulingConfiguration {
    @Bean
    @ConditionalOnProperty(name = "app.notifications.outbox.scheduling-enabled", havingValue = "true", matchIfMissing = true)
    OutboxPoller outboxPoller(NotificationOutboxDispatcher dispatcher) {
        return new OutboxPoller(dispatcher);
    }

    static final class OutboxPoller {
        private final NotificationOutboxDispatcher dispatcher;

        OutboxPoller(NotificationOutboxDispatcher dispatcher) {
            this.dispatcher = dispatcher;
        }

        @Scheduled(fixedDelayString = "${app.notifications.outbox.poll-interval-ms:2000}")
        public void poll() {
            dispatcher.dispatch();
        }
    }
}
