package com.example.demo.search.service;

import com.example.demo.threads.event.kafka.KafkaContentEventPublisher;
import com.example.demo.threads.event.kafka.KafkaContentEventReader;
import io.micrometer.core.instrument.MeterRegistry;
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
    PilotSearchConsumer pilotSearchConsumer(KafkaContentEventReader reader, KafkaContentEventPublisher publisher,
                                            ContentSearchEventDecoder decoder, ContentSearchProjection projection,
                                            ContentSearchPilotIndexes indexes, MeterRegistry metrics) {
        return new PilotSearchConsumer(reader, publisher, decoder, projection, indexes.kafka(), metrics);
    }

    record PilotSearchConsumer(KafkaContentEventReader reader, KafkaContentEventPublisher publisher,
                               ContentSearchEventDecoder decoder, ContentSearchProjection projection,
                               String index, MeterRegistry metrics) {
        @Scheduled(fixedDelayString = "${app.search.kafka-pilot.poll-delay-ms:1000}",
                scheduler = "kafkaSearchScheduler")
        public void poll() {
            reader.pollRecords(record -> {
                try {
                    projection.applyTo(index, decoder.decode(record.value()));
                } catch (InvalidContentEventException invalid) {
                    publisher.quarantine(record, invalid.getMessage());
                    metrics.counter("bluealbum.content.kafka.search.quarantined").increment();
                }
            });
        }
    }
}
