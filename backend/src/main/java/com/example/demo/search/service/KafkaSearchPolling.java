package com.example.demo.search.service;

import com.example.demo.threads.event.kafka.KafkaContentEventReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"app.search.enabled", "app.content.events.enabled", "app.content.events.kafka.enabled"},
        havingValue = "true")
public class KafkaSearchPolling {
    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler kafkaSearchScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("content-kafka-search-");
        return scheduler;
    }

    @Bean
    PilotSearchConsumer pilotSearchConsumer(KafkaContentEventReader reader, ContentSearchEventDecoder decoder,
                                            ContentSearchProjection projection, ContentSearchPilotIndexes indexes) {
        return new PilotSearchConsumer(reader, decoder, projection, indexes.kafka());
    }

    record PilotSearchConsumer(KafkaContentEventReader reader, ContentSearchEventDecoder decoder,
                               ContentSearchProjection projection, String index) {
        @Scheduled(fixedDelayString = "${app.search.kafka-pilot.poll-delay-ms:1000}",
                scheduler = "kafkaSearchScheduler")
        public void poll() {
            reader.pollAndProcess(body -> projection.applyTo(index, decoder.decode(body)));
        }
    }
}
