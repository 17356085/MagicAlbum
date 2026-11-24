package com.example.demo.threads.event.service;

import com.example.demo.threads.event.dto.ContentEventMessage;
import com.example.demo.threads.event.kafka.KafkaContentEventProperties;
import com.example.demo.threads.event.port.ContentEventPublisher.PublishException;
import com.example.demo.threads.event.repo.ContentEventOutboxRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Kafka confirmation and retry state is independent of the RabbitMQ outbox state. */
@Service
@ConditionalOnProperty(name = {"app.content.events.enabled", "app.content.events.kafka.enabled"},
        havingValue = "true")
public class ContentKafkaOutbox {
    private static final int MAX_RETRY_SECONDS = 300;

    private final JdbcTemplate jdbc;
    private final ContentEventOutboxRepository events;
    private final int maxAttempts;
    private final int retryInitialSeconds;

    public ContentKafkaOutbox(JdbcTemplate jdbc,
                              ContentEventOutboxRepository events,
                              KafkaContentEventProperties properties) {
        properties.validate();
        this.jdbc = jdbc;
        this.events = events;
        this.maxAttempts = properties.getMaxAttempts();
        this.retryInitialSeconds = properties.getRetryInitialSeconds();
    }

    @Transactional
    public Optional<Delivery> claim() {
        List<Candidate> candidates = jdbc.query("""
                SELECT outbox_id, attempt_count
                  FROM content_event_kafka_delivery
                 WHERE published_at IS NULL
                   AND failed_at IS NULL
                   AND available_at <= UTC_TIMESTAMP(6)
                   AND (lease_until IS NULL OR lease_until <= UTC_TIMESTAMP(6))
                 ORDER BY available_at, outbox_id
                 LIMIT 1
                 FOR UPDATE SKIP LOCKED
                """, (resultSet, rowNumber) -> new Candidate(
                resultSet.getLong("outbox_id"), resultSet.getInt("attempt_count")));
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        Candidate candidate = candidates.getFirst();
        if (candidate.attemptCount() >= maxAttempts) {
            terminalizeExpired(candidate.id());
            return Optional.empty();
        }

        String token = UUID.randomUUID().toString();
        int updated = jdbc.update("""
                UPDATE content_event_kafka_delivery
                   SET attempt_count = attempt_count + 1,
                       lease_token = ?,
                       lease_until = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 SECOND)
                 WHERE outbox_id = ?
                   AND published_at IS NULL
                   AND failed_at IS NULL
                   AND (lease_until IS NULL OR lease_until <= UTC_TIMESTAMP(6))
                """, token, candidate.id());
        if (updated != 1) {
            return Optional.empty();
        }
        var event = events.findById(candidate.id()).orElseThrow();
        return Optional.of(new Delivery(
                candidate.id(), token, ContentEventOutboxService.toMessage(event),
                candidate.attemptCount() + 1));
    }

    @Transactional
    public boolean confirmed(Delivery delivery) {
        if (delivery == null) {
            return false;
        }
        return jdbc.update("""
                UPDATE content_event_kafka_delivery
                   SET published_at = UTC_TIMESTAMP(6),
                       lease_token = NULL,
                       lease_until = NULL,
                       last_error = NULL
                 WHERE outbox_id = ?
                   AND lease_token = ?
                   AND lease_until > UTC_TIMESTAMP(6)
                   AND published_at IS NULL
                   AND failed_at IS NULL
                """, delivery.id(), delivery.token()) == 1;
    }

    @Transactional
    public boolean retry(Delivery delivery, RuntimeException failure) {
        if (delivery == null) {
            return false;
        }
        String code = safeErrorCode(failure);
        Integer attemptCount = jdbc.query("""
                SELECT attempt_count
                  FROM content_event_kafka_delivery
                 WHERE outbox_id = ?
                   AND lease_token = ?
                   AND lease_until > UTC_TIMESTAMP(6)
                   AND published_at IS NULL
                   AND failed_at IS NULL
                 FOR UPDATE
                """, resultSet -> resultSet.next() ? resultSet.getInt(1) : null,
                delivery.id(), delivery.token());
        if (attemptCount == null) {
            return false;
        }
        if (attemptCount >= maxAttempts) {
            return terminalize(delivery, code);
        }

        long delaySeconds = retryDelaySeconds(attemptCount);
        return jdbc.update("""
                UPDATE content_event_kafka_delivery
                   SET available_at = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL ? SECOND),
                       lease_token = NULL,
                       lease_until = NULL,
                       last_error = ?
                 WHERE outbox_id = ?
                   AND lease_token = ?
                   AND lease_until > UTC_TIMESTAMP(6)
                   AND published_at IS NULL
                   AND failed_at IS NULL
                   AND attempt_count < ?
                """, delaySeconds, code, delivery.id(), delivery.token(), maxAttempts) == 1;
    }

    @Transactional
    public boolean replayFailed(long id) {
        return jdbc.update("""
                UPDATE content_event_kafka_delivery
                   SET available_at = UTC_TIMESTAMP(6),
                       attempt_count = 0,
                       failed_at = NULL,
                       lease_token = NULL,
                       lease_until = NULL,
                       last_error = NULL
                 WHERE outbox_id = ?
                   AND published_at IS NULL
                   AND failed_at IS NOT NULL
                """, id) == 1;
    }

    @Transactional(readOnly = true)
    public DeliveryCounts deliveryCounts() {
        return jdbc.queryForObject("""
                SELECT COUNT(CASE WHEN delivery.failed_at IS NULL
                                      AND (delivery.lease_until IS NULL
                                           OR delivery.lease_until <= UTC_TIMESTAMP(6))
                                  THEN 1 END) AS pending_count,
                       COUNT(CASE WHEN delivery.failed_at IS NULL
                                      AND delivery.lease_until > UTC_TIMESTAMP(6)
                                  THEN 1 END) AS in_flight_count,
                       COUNT(CASE WHEN delivery.failed_at IS NOT NULL
                                  THEN 1 END) AS failed_count,
                       COALESCE(
                           GREATEST(
                               0,
                               TIMESTAMPDIFF(
                                   SECOND,
                                   MIN(CASE WHEN delivery.failed_at IS NULL
                                                 AND (delivery.lease_until IS NULL
                                                      OR delivery.lease_until <= UTC_TIMESTAMP(6))
                                            THEN source_event.occurred_at END),
                                   UTC_TIMESTAMP(6))),
                           0) AS oldest_pending_seconds
                  FROM content_event_kafka_delivery delivery
                  JOIN content_event_outbox source_event ON source_event.id = delivery.outbox_id
                 WHERE delivery.published_at IS NULL
                """, (resultSet, rowNumber) -> new DeliveryCounts(
                resultSet.getLong("pending_count"),
                resultSet.getLong("in_flight_count"),
                resultSet.getLong("failed_count"),
                resultSet.getLong("oldest_pending_seconds")));
    }

    @Transactional(readOnly = true)
    public Diagnostics diagnostics() {
        DeliveryCounts counts = deliveryCounts();
        return new Diagnostics(
                counts.pending(),
                counts.inFlight(),
                counts.failed(),
                counts.oldestPendingSeconds(),
                failedDeliveries());
    }

    private List<FailedDelivery> failedDeliveries() {
        return jdbc.query("""
                SELECT outbox_id, attempt_count, COALESCE(NULLIF(last_error, ''), 'UNKNOWN') AS error_code
                  FROM content_event_kafka_delivery
                 WHERE failed_at IS NOT NULL
                 ORDER BY failed_at, outbox_id
                 LIMIT 50
                """, (resultSet, rowNumber) -> new FailedDelivery(
                resultSet.getLong("outbox_id"),
                resultSet.getInt("attempt_count"),
                resultSet.getString("error_code")));
    }

    private boolean terminalize(Delivery delivery, String code) {
        return jdbc.update("""
                UPDATE content_event_kafka_delivery
                   SET available_at = UTC_TIMESTAMP(6),
                       failed_at = UTC_TIMESTAMP(6),
                       lease_token = NULL,
                       lease_until = NULL,
                       last_error = ?
                 WHERE outbox_id = ?
                   AND lease_token = ?
                   AND lease_until > UTC_TIMESTAMP(6)
                   AND published_at IS NULL
                   AND failed_at IS NULL
                   AND attempt_count >= ?
                """, code, delivery.id(), delivery.token(), maxAttempts) == 1;
    }

    private void terminalizeExpired(long id) {
        jdbc.update("""
                UPDATE content_event_kafka_delivery
                   SET failed_at = UTC_TIMESTAMP(6),
                       lease_token = NULL,
                       lease_until = NULL,
                       last_error = 'MAX_ATTEMPTS'
                 WHERE outbox_id = ?
                   AND published_at IS NULL
                   AND failed_at IS NULL
                   AND attempt_count >= ?
                   AND (lease_until IS NULL OR lease_until <= UTC_TIMESTAMP(6))
                """, id, maxAttempts);
    }

    private long retryDelaySeconds(int attemptCount) {
        long delay = retryInitialSeconds;
        for (int attempt = 1; attempt < attemptCount && delay < MAX_RETRY_SECONDS; attempt++) {
            delay = Math.min(MAX_RETRY_SECONDS, delay * 2);
        }
        return delay;
    }

    private static String safeErrorCode(RuntimeException failure) {
        if (failure instanceof PublishException publish && publish.code() != null) {
            return publish.code().name();
        }
        return "TRANSPORT";
    }

    private record Candidate(long id, int attemptCount) {
    }

    public record Delivery(long id, String token, ContentEventMessage event, int attemptCount) {
    }

    public record DeliveryCounts(long pending, long inFlight, long failed, long oldestPendingSeconds) {
    }

    public record FailedDelivery(long id, int attemptCount, String errorCode) {
    }

    public record Diagnostics(long pending,
                              long inFlight,
                              long failed,
                              long oldestPendingSeconds,
                              List<FailedDelivery> failedDeliveries) {
        public Diagnostics {
            failedDeliveries = List.copyOf(failedDeliveries);
        }
    }
}
