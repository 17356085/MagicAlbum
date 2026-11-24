package com.example.demo.threads.event.kafka;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaContentEventConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(KafkaContentEventConfiguration.class);

    @Test
    void disabled_by_default_and_when_global_events_are_disabled() {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean(KafkaContentEventPublisher.class)
                .doesNotHaveBean(KafkaContentEventReader.class));
        contextRunner.withPropertyValues("app.content.events.kafka.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(KafkaContentEventPublisher.class)
                        .doesNotHaveBean(KafkaContentEventReader.class));
    }

    @Test
    void producer_uses_zero_linger_and_bounded_two_stage_timeout() {
        KafkaContentEventProperties properties = new KafkaContentEventProperties();

        var producer = KafkaContentEventConfiguration.producerProperties(properties);

        assertThat(producer.get("linger.ms")).isEqualTo(0);
        assertThat(producer.get("max.block.ms")).isEqualTo(5000);
        assertThat(producer.get("request.timeout.ms")).isEqualTo(5000);
        assertThat(producer.get("delivery.timeout.ms")).isEqualTo(5000);
    }

    @Test
    void timeout_budget_cannot_reach_sql_lease() {
        KafkaContentEventProperties properties = new KafkaContentEventProperties();
        properties.setMaxBlockTimeout(Duration.ofSeconds(20));
        properties.setSendTimeout(Duration.ofSeconds(10));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("below the 30-second SQL lease");
    }

    @Test
    void kafka_delivery_retry_defaults_are_bounded() {
        KafkaContentEventProperties properties = new KafkaContentEventProperties();

        properties.validate();

        assertThat(properties.getRetryInitialSeconds()).isEqualTo(5);
        assertThat(properties.getMaxAttempts()).isEqualTo(6);
    }

    @Test
    void kafka_delivery_max_attempts_must_be_between_one_and_twenty() {
        KafkaContentEventProperties properties = new KafkaContentEventProperties();
        properties.setMaxAttempts(21);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-attempts must be between 1 and 20");
    }
}
