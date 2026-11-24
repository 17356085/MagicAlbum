package com.example.demo.threads.event.service;

import com.example.demo.threads.event.entity.ContentEventStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@Profile("observability")
@ConditionalOnProperty(name = "app.content.events.enabled", havingValue = "true")
public class ContentEventOutboxMetrics implements MeterBinder {
    private final ContentEventOutboxService outbox;

    public ContentEventOutboxMetrics(ContentEventOutboxService outbox) {
        this.outbox = outbox;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (var status : new ContentEventStatus[]{ContentEventStatus.PENDING,
                ContentEventStatus.IN_FLIGHT, ContentEventStatus.FAILED}) {
            Gauge.builder("bluealbum.content.outbox.rows", outbox, service -> service.count(status))
                    .description("Durable content event delivery rows by dispatch status")
                    .tag("status", status.name().toLowerCase(Locale.ROOT))
                    .register(registry);
        }
        Gauge.builder("bluealbum.content.outbox.oldest.pending", outbox,
                        ContentEventOutboxService::oldestPendingAgeSeconds)
                .description("Age of the oldest pending content event delivery")
                .baseUnit("seconds")
                .register(registry);
    }
}
