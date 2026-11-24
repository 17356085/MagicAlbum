package com.example.demo.threads.event.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.function.ToDoubleFunction;

@Component
@Profile("observability")
@ConditionalOnProperty(name = {"app.content.events.enabled", "app.content.events.kafka.enabled"}, havingValue = "true")
public class ContentKafkaOutboxMetrics implements MeterBinder {
    private final ContentKafkaOutbox outbox;

    public ContentKafkaOutboxMetrics(ContentKafkaOutbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        rows(registry, "pending", ContentKafkaOutbox.DeliveryCounts::pending);
        rows(registry, "in_flight", ContentKafkaOutbox.DeliveryCounts::inFlight);
        rows(registry, "failed", ContentKafkaOutbox.DeliveryCounts::failed);
        Gauge.builder("bluealbum.content.kafka.outbox.oldest.pending", outbox,
                        source -> source.deliveryCounts().oldestPendingSeconds())
                .description("Age of the oldest unconfirmed Kafka delivery awaiting its next claim")
                .baseUnit("seconds")
                .register(registry);
    }

    private void rows(MeterRegistry registry, String status,
                      ToDoubleFunction<ContentKafkaOutbox.DeliveryCounts> count) {
        Gauge.builder("bluealbum.content.kafka.outbox.rows", outbox, source -> count.applyAsDouble(source.deliveryCounts()))
                .description("Durable Kafka content delivery rows by dispatch status")
                .tag("status", status)
                .register(registry);
    }
}
