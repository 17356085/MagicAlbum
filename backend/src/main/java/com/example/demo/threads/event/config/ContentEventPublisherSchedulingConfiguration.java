package com.example.demo.threads.event.config;

import com.example.demo.threads.event.service.ContentEventOutboxDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Starts publishing only when this application instance owns content events. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.content.events.enabled", havingValue = "true")
public class ContentEventPublisherSchedulingConfiguration {
    @Bean
    @ConditionalOnProperty(name = "app.content.events.publisher-scheduler-enabled", havingValue = "true",
            matchIfMissing = true)
    public ContentEventOutboxPoller contentEventOutboxPoller(
            ContentEventOutboxDispatcher dispatcher) {
        return new ContentEventOutboxPoller(dispatcher);
    }

    static final class ContentEventOutboxPoller {
        private final ContentEventOutboxDispatcher dispatcher;

        ContentEventOutboxPoller(ContentEventOutboxDispatcher dispatcher) {
            this.dispatcher = dispatcher;
        }

        @Scheduled(fixedDelayString = "${app.content.events.poll-interval-ms:2000}")
        public void poll() {
            dispatcher.dispatchOnce();
        }
    }
}
