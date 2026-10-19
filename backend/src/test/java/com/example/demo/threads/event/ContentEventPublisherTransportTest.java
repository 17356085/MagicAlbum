package com.example.demo.threads.event;

import com.example.demo.threads.event.dto.ContentEventMessage;
import com.example.demo.threads.event.service.ContentEventPublisherTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ContentEventPublisherTransportTest {
    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ContentEventMessage event = new ContentEventMessage(
            "event-1", 1, "THREAD", 7L, 2L, "THREAD_UPSERTED",
            Instant.parse("2026-10-19T00:00:00Z"), "request-1");

    @Test
    void ack_without_return_is_success_and_message_is_persistent_json() {
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            assertThat(message.getMessageProperties().getDeliveryMode())
                    .isEqualTo(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
            assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
            assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.eventId());
            var body = mapper.readTree(message.getBody());
            assertThat(body.get("occurredAt").asText()).isEqualTo("2026-10-19T00:00:00Z");
            assertThat(body.size()).isEqualTo(8);
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        ContentEventPublisherTransport transport = transport(Duration.ofSeconds(1));

        transport.publish(event);

        verify(template).setMandatory(true);
    }

    @Test
    void nack_is_reported_without_persisting_broker_reason() {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "secret broker detail"));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        ContentEventPublisherTransport transport = transport(Duration.ofSeconds(1));

        assertThatThrownBy(() -> transport.publish(event))
                .isInstanceOf(ContentEventPublisherTransport.PublishException.class)
                .extracting(ContentEventPublisherTransport.PublishException.class::cast)
                .extracting(ContentEventPublisherTransport.PublishException::code)
                .isEqualTo(ContentEventPublisherTransport.Code.CONFIRM_NACK);
    }

    @Test
    void returned_message_is_failure_even_when_confirm_is_ack() {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.setReturned(mock(org.springframework.amqp.core.ReturnedMessage.class));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        ContentEventPublisherTransport transport = transport(Duration.ofSeconds(1));

        assertThatThrownBy(() -> transport.publish(event))
                .isInstanceOf(ContentEventPublisherTransport.PublishException.class)
                .extracting(ContentEventPublisherTransport.PublishException.class::cast)
                .extracting(ContentEventPublisherTransport.PublishException::code)
                .isEqualTo(ContentEventPublisherTransport.Code.RETURNED);
    }

    @Test
    void confirmation_wait_is_bounded() {
        doAnswer(invocation -> null)
                .when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        ContentEventPublisherTransport transport = transport(Duration.ofMillis(5));

        assertThatThrownBy(() -> transport.publish(event))
                .isInstanceOf(ContentEventPublisherTransport.PublishException.class)
                .extracting(ContentEventPublisherTransport.PublishException.class::cast)
                .extracting(ContentEventPublisherTransport.PublishException::code)
                .isEqualTo(ContentEventPublisherTransport.Code.CONFIRM_TIMEOUT);
    }

    @Test
    void quarantine_publishes_safe_metadata_without_copying_sensitive_original_body() throws Exception {
        String eventId = "123e4567-e89b-12d3-a456-426614174000";
        byte[] original = ("{\"eventId\":\"" + eventId
                + "\",\"aggregateId\":7,\"aggregateVersion\":2,\"schemaVersion\":9"
                + ",\"requestId\":\"secret-request\",\"content\":\"secret-content\"}")
                .getBytes(StandardCharsets.UTF_8);
        AtomicReference<Message> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(2));
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        ContentEventPublisherTransport transport = transport(Duration.ofSeconds(1));

        transport.quarantine(original);

        verify(template).send(eq("content.events.dlx"), eq("quarantined"), any(Message.class),
                any(CorrelationData.class));
        var body = mapper.readTree(captured.get().getBody());
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(original);
        assertThat(body.get("bodySha256").asText()).isEqualTo(HexFormat.of().formatHex(digest));
        assertThat(body.get("quarantineId").asText()).isEqualTo(UUID.nameUUIDFromBytes(digest).toString());
        assertThat(body.get("reason").asText()).isEqualTo("INVALID_ENVELOPE");
        assertThat(body.get("eventId").asText()).isEqualTo(eventId);
        assertThat(body.get("aggregateId").asLong()).isEqualTo(7);
        assertThat(body.get("aggregateVersion").asLong()).isEqualTo(2);
        assertThat(body.get("schemaVersion").asLong()).isEqualTo(9);
        assertThat(body.has("requestId")).isFalse();
        assertThat(body.has("content")).isFalse();
        assertThat(new String(captured.get().getBody(), StandardCharsets.UTF_8))
                .doesNotContain("secret-request", "secret-content");
    }

    private ContentEventPublisherTransport transport(Duration timeout) {
        return new ContentEventPublisherTransport(template, mapper, timeout);
    }
}
