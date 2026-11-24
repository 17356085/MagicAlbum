package com.example.demo.threads.event.kafka;

import com.example.demo.threads.event.dto.ContentEventMessage;
import com.example.demo.threads.event.port.ContentEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Confirmed Kafka adapter for the stable content-event envelope.
 *
 * <p>The producer key is {@code aggregateType + ":" + aggregateId}, so all
 * versions of one aggregate are routed to the same Kafka partition. A retry can
 * publish an earlier version after a later one. The original event id is preserved on every
 * retry.</p>
 */
public class KafkaContentEventPublisher implements ContentEventPublisher {
    private static final int QUARANTINE_COPY_LIMIT = 8192;

    private final Producer<String, byte[]> producer;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final Duration sendTimeout;
    private final Duration closeTimeout;
    private final AtomicBoolean closed = new AtomicBoolean();

    public KafkaContentEventPublisher(Producer<String, byte[]> producer,
                                      ObjectMapper objectMapper,
                                      String topic,
                                      Duration sendTimeout,
                                      Duration closeTimeout) {
        if (producer == null || objectMapper == null) {
            throw new IllegalArgumentException("Kafka publisher dependencies are required");
        }
        requireText(topic, "topic");
        requireDuration(sendTimeout, "sendTimeout");
        requireDuration(closeTimeout, "closeTimeout");
        this.producer = producer;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.sendTimeout = sendTimeout;
        this.closeTimeout = closeTimeout;
    }

    /** Stable key shared by every retry and event version for one aggregate. */
    public static String aggregateKey(ContentEventMessage event) {
        if (event == null || event.aggregateType() == null || event.aggregateType().isBlank()) {
            throw new ContentEventPublisher.PublishException(ContentEventPublisher.Code.SERIALIZATION);
        }
        return event.aggregateType() + ":" + event.aggregateId();
    }

    @Override
    public void publish(ContentEventMessage event) {
        if (closed.get()) {
            throw new ContentEventPublisher.PublishException(ContentEventPublisher.Code.TRANSPORT);
        }
        if (event == null || event.eventId() == null || event.eventId().isBlank()) {
            throw new ContentEventPublisher.PublishException(ContentEventPublisher.Code.SERIALIZATION);
        }

        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(event);
        } catch (JsonProcessingException exception) {
            throw new ContentEventPublisher.PublishException(ContentEventPublisher.Code.SERIALIZATION);
        } catch (RuntimeException exception) {
            throw new ContentEventPublisher.PublishException(ContentEventPublisher.Code.TRANSPORT);
        }

        String key = aggregateKey(event);
        sendConfirmed(new ProducerRecord<>(topic, key, payload));
    }

    public void quarantine(ConsumerRecord<String, byte[]> source, String errorCode) {
        if (closed.get() || !topic.equals(source.topic())) {
            throw new ContentEventPublisher.PublishException(ContentEventPublisher.Code.TRANSPORT);
        }
        byte[] body = source.value() == null ? new byte[0] : source.value();
        byte[] retained = body.length <= QUARANTINE_COPY_LIMIT ? body : Arrays.copyOf(body, QUARANTINE_COPY_LIMIT);
        String coordinate = source.topic() + ":" + source.partition() + ":" + source.offset();
        var record = new ProducerRecord<String, byte[]>(topic + ".quarantine", coordinate, retained);
        record.headers().add("error-code", errorCode.getBytes(StandardCharsets.US_ASCII));
        record.headers().add("original-sha256", sha256(body).getBytes(StandardCharsets.US_ASCII));
        record.headers().add("original-byte-count", Integer.toString(body.length).getBytes(StandardCharsets.US_ASCII));
        sendConfirmed(record);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private void sendConfirmed(ProducerRecord<String, byte[]> record) {
        try {
            Future<?> confirmation = producer.send(record);
            confirmation.get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            // The broker outcome is unknown when confirmation did not arrive in time.
            throw new ContentEventPublisher.PublishException(ContentEventPublisher.Code.CONFIRM_TIMEOUT);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ContentEventPublisher.PublishException(ContentEventPublisher.Code.CONFIRM_TIMEOUT);
        } catch (ExecutionException exception) {
            throw failedPublish(exception.getCause());
        } catch (RuntimeException exception) {
            throw failedPublish(exception);
        }
    }

    private static ContentEventPublisher.PublishException failedPublish(Throwable failure) {
        if (failure instanceof TimeoutException) {
            return new ContentEventPublisher.PublishException(ContentEventPublisher.Code.CONFIRM_TIMEOUT);
        }
        return new ContentEventPublisher.PublishException(ContentEventPublisher.Code.TRANSPORT);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireDuration(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative() || !fitsKafkaMillis(value)) {
            throw new IllegalArgumentException(name + " must be a positive Kafka millisecond timeout");
        }
    }

    private static boolean fitsKafkaMillis(Duration value) {
        try {
            long millis = value.toMillis();
            return millis >= 1 && millis <= Integer.MAX_VALUE;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    /** Close is idempotent and bounded so Spring shutdown cannot leak the client. */
    @PreDestroy
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                producer.close(closeTimeout);
            } catch (RuntimeException ignored) {
                // Shutdown must not mask the original application failure.
            }
        }
    }
}
