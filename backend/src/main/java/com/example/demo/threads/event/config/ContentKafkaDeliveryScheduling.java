package com.example.demo.threads.event.config;

import com.example.demo.threads.event.kafka.KafkaContentEventPublisher;
import com.example.demo.threads.event.service.ContentKafkaOutbox;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"app.content.events.enabled", "app.content.events.kafka.enabled"},
        havingValue = "true")
public class ContentKafkaDeliveryScheduling {
    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler contentKafkaDeliveryScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("content-kafka-publish-");
        return scheduler;
    }

    @Bean
    PilotDelivery pilotDelivery(ContentKafkaOutbox outbox, KafkaContentEventPublisher publisher) {
        return new PilotDelivery(outbox, publisher);
    }

    record PilotDelivery(ContentKafkaOutbox outbox, KafkaContentEventPublisher publisher) {
        @Scheduled(fixedDelayString = "${app.content.events.kafka.dispatch-poll-ms:1000}",
                scheduler = "contentKafkaDeliveryScheduler")
        public void deliver() {
            for (int batch = 0; batch < 8; batch++) {
                var next = outbox.claim();
                if (next.isEmpty()) {
                    return;
                }
                var delivery = next.get();
                try {
                    publisher.publish(delivery.event());
                } catch (RuntimeException unavailable) {
                    outbox.retry(delivery, unavailable);
                    return;
                }
                outbox.confirmed(delivery);
            }
        }
    }
}
