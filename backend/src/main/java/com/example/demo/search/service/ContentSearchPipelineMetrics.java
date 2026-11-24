package com.example.demo.search.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true")
class ContentSearchPipelineMetrics {
    private final DistributionSummary indexLag;
    private final Counter failed;
    private final Counter replayed;
    private final Counter quarantined;

    ContentSearchPipelineMetrics(MeterRegistry registry) {
        indexLag = DistributionSummary.builder("bluealbum.content.search.index.lag")
                .description("Content event age when its index projection succeeds")
                .baseUnit("seconds")
                .register(registry);
        failed = registry.counter("bluealbum.content.search.failed");
        replayed = registry.counter("bluealbum.content.search.replayed");
        quarantined = registry.counter("bluealbum.content.search.quarantined");
    }

    void indexed(Instant occurredAt) {
        Duration age = Duration.between(occurredAt, Instant.now());
        indexLag.record(Math.max(0, age.getSeconds() + age.getNano() / 1_000_000_000.0));
    }

    void failed() {
        failed.increment();
    }

    void replayed() {
        replayed.increment();
    }

    void quarantined() {
        quarantined.increment();
    }
}
