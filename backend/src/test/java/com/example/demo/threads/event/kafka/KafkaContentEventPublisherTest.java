package com.example.demo.threads.event.kafka;

import com.example.demo.threads.event.dto.ContentEventMessage;
import com.example.demo.threads.event.port.ContentEventPublisher.Code;
import com.example.demo.threads.event.port.ContentEventPublisher.PublishException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaContentEventPublisherTest {
    private final Producer<String, byte[]> producer = mock(Producer.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ContentEventMessage event = new ContentEventMessage(
            "event-1", 1, "THREAD", 7L, 2L, "THREAD_UPSERTED",
            Instant.parse("2026-10-19T00:00:00Z"), "request-1");

    @Test
    void sends_original_envelope_with_stable_aggregate_key() throws Exception {
        when(producer.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        KafkaContentEventPublisher publisher = publisher(Duration.ofSeconds(1));

        publisher.publish(event);

        var capture = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer).send(capture.capture());
        ProducerRecord<?, ?> record = capture.getValue();
        assertThat(record.topic()).isEqualTo("content.events.v1");
        assertThat(record.key()).isEqualTo("THREAD:7");
        var body = mapper.readTree((byte[]) record.value());
        assertThat(body.get("eventId").asText()).isEqualTo(event.eventId());
        assertThat(body.get("occurredAt").asText()).isEqualTo("2026-10-19T00:00:00Z");
    }

    @Test
    void quarantine_preserves_broker_coordinates_and_full_digest_when_copy_is_bounded() throws Exception {
        when(producer.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        byte[] oversized = new byte[9000];
        oversized[oversized.length - 1] = 42;

        publisher(Duration.ofSeconds(1)).quarantine(
                new ConsumerRecord<>("content.events.v1", 2, 19L, "THREAD:7", oversized), "INVALID_ENVELOPE_SIZE");

        var capture = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer).send(capture.capture());
        var record = capture.getValue();
        assertThat(record.topic()).isEqualTo("content.events.v1.quarantine");
        assertThat(record.key()).isEqualTo("content.events.v1:2:19");
        assertThat((byte[]) record.value()).containsExactly(Arrays.copyOf(oversized, 8192));
        assertThat(record.headers().lastHeader("original-byte-count").value())
                .containsExactly("9000".getBytes(StandardCharsets.US_ASCII));
        assertThat(new String(record.headers().lastHeader("original-sha256").value(), StandardCharsets.US_ASCII))
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(oversized)));
    }

    @Test
    void confirmation_timeout_is_reported_as_unknown_outcome() {
        when(producer.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());
        KafkaContentEventPublisher publisher = publisher(Duration.ofMillis(10));

        assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOfSatisfying(PublishException.class,
                        failure -> assertThat(failure.code()).isEqualTo(Code.CONFIRM_TIMEOUT));
    }

    @Test
    void failed_future_is_reported_as_transport_failure() {
        CompletableFuture<RecordMetadata> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(producer.send(any(ProducerRecord.class))).thenReturn(failed);
        KafkaContentEventPublisher publisher = publisher(Duration.ofSeconds(1));

        assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOfSatisfying(PublishException.class,
                        failure -> assertThat(failure.code()).isEqualTo(Code.TRANSPORT));
    }

    @Test
    void close_is_bounded_and_idempotent() {
        KafkaContentEventPublisher publisher = publisher(Duration.ofSeconds(1));

        publisher.close();
        publisher.close();

        verify(producer).close(Duration.ofSeconds(1));
    }

    private KafkaContentEventPublisher publisher(Duration sendTimeout) {
        return new KafkaContentEventPublisher(
                producer, mapper, "content.events.v1", sendTimeout, Duration.ofSeconds(1));
    }
}
