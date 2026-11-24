package com.example.demo.threads.event.kafka;

import com.example.demo.threads.event.dto.ContentEventMessage;
import com.example.demo.threads.event.port.ContentEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Confirmed Kafka adapter for the stable content-event envelope.
 *
 * <p>The producer key is {@code aggregateType + ":" + aggregateId}, so all
 * versions of one aggregate remain ordered in one Kafka partition. The event
 * id is never regenerated: the JSON payload contains the original id on every
 * retry.</p>
 */
public class KafkaContentEventPublisher implements ContentEventPublisher {
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
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, payload);
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
