package com.example.demo.threads.event.service;

import com.example.demo.threads.event.dto.ContentEventMessage;
import com.example.demo.threads.event.port.ContentEventPublisher.PublishException;
import com.example.demo.threads.event.repo.ContentEventOutboxRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** Kafka confirmation and retry state is independent of the RabbitMQ outbox state. */
@Service
@ConditionalOnProperty(name = {"app.content.events.enabled", "app.content.events.kafka.enabled"},
        havingValue = "true")
public class ContentKafkaOutbox {
    private final JdbcTemplate jdbc;
    private final ContentEventOutboxRepository events;

    public ContentKafkaOutbox(JdbcTemplate jdbc, ContentEventOutboxRepository events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional
    public Optional<Delivery> claim() {
        var candidates = jdbc.queryForList("""
                SELECT outbox_id FROM content_event_kafka_delivery
                WHERE published_at IS NULL AND available_at <= UTC_TIMESTAMP(6)
                  AND (lease_until IS NULL OR lease_until <= UTC_TIMESTAMP(6))
                ORDER BY available_at, outbox_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, Long.class);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        long id = candidates.getFirst();
        String token = UUID.randomUUID().toString();
        jdbc.update("""
                UPDATE content_event_kafka_delivery
                SET lease_token = ?, lease_until = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 SECOND)
                WHERE outbox_id = ?
                """, token, id);
        var event = events.findById(id).orElseThrow();
        return Optional.of(new Delivery(id, token, ContentEventOutboxService.toMessage(event)));
    }

    @Transactional
    public boolean confirmed(Delivery delivery) {
        return jdbc.update("""
                UPDATE content_event_kafka_delivery
                SET published_at = UTC_TIMESTAMP(6), lease_token = NULL, lease_until = NULL, last_error = NULL
                WHERE outbox_id = ? AND lease_token = ? AND lease_until > UTC_TIMESTAMP(6)
                """, delivery.id(), delivery.token()) == 1;
    }

    @Transactional
    public boolean retry(Delivery delivery, RuntimeException failure) {
        String code = failure instanceof PublishException publish ? publish.code().name() : "TRANSPORT";
        return jdbc.update("""
                UPDATE content_event_kafka_delivery
                SET available_at = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 5 SECOND),
                    lease_token = NULL, lease_until = NULL, last_error = ?
                WHERE outbox_id = ? AND lease_token = ? AND lease_until > UTC_TIMESTAMP(6)
                """, code, delivery.id(), delivery.token()) == 1;
    }

    public record Delivery(long id, String token, ContentEventMessage event) { }
}
