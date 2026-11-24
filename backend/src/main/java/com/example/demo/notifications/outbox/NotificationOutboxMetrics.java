package com.example.demo.notifications.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@Profile("observability")
public class NotificationOutboxMetrics implements MeterBinder {
    private final NotificationOutboxService outbox;

    public NotificationOutboxMetrics(NotificationOutboxService outbox) {
        this.outbox = outbox;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (var status : new NotificationOutboxStatus[]{NotificationOutboxStatus.PENDING,
                NotificationOutboxStatus.IN_FLIGHT, NotificationOutboxStatus.FAILED}) {
            Gauge.builder("bluealbum.notification.outbox.rows", outbox, service -> service.count(status))
                    .description("Durable notification delivery rows by dispatch status")
                    .tag("status", status.name().toLowerCase(Locale.ROOT))
                    .register(registry);
        }
        Gauge.builder("bluealbum.notification.outbox.oldest.pending", outbox,
                        NotificationOutboxService::oldestPendingAgeSeconds)
                .description("Age of the oldest pending notification delivery")
                .baseUnit("seconds")
                .register(registry);
    }
}
