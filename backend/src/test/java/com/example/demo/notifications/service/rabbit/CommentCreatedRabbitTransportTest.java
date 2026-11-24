package com.example.demo.notifications.service.rabbit;

import com.example.demo.notifications.dto.NotificationDto;
import com.example.demo.notifications.outbox.NotificationOutboxService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentCreatedRabbitTransportTest {
    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID eventId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void sends_persistent_versioned_json_and_decodes_notification_ack() throws Exception {
        NotificationDto dto = new NotificationDto(
                900L, "reply", "Title", "Content", false,
                null, 7L, 8L, "post");
        Message response = new Message(objectMapper.writeValueAsBytes(dto), new MessageProperties());
        when(template.sendAndReceive(eq(""), eq(CommentCreatedRabbitTransport.QUEUE), any(Message.class)))
                .thenReturn(response);
        CommentCreatedRabbitTransport transport = new CommentCreatedRabbitTransport(
                template, objectMapper, Duration.ofSeconds(1));

        NotificationDto received = transport.deliver(delivery());

        assertThat(received.getId()).isEqualTo(900L);
        var request = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(template).sendAndReceive(eq(""), eq(CommentCreatedRabbitTransport.QUEUE), request.capture());
        assertThat(request.getValue().getMessageProperties().getDeliveryMode())
                .isEqualTo(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
        assertThat(request.getValue().getMessageProperties().getMessageId()).isEqualTo(eventId.toString());
        var body = objectMapper.readTree(request.getValue().getBody());
        assertThat(body.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(body.get("eventType").asText()).isEqualTo("COMMENT_CREATED");
        assertThat(body.get("commentId").asLong()).isEqualTo(8L);
        assertThat(body.get("threadId").asLong()).isEqualTo(7L);
        assertThat(body.get("recipientUserId").asLong()).isEqualTo(101L);
        assertThat(body.get("title").asText()).isEqualTo("Title");
        assertThat(body.get("content").asText()).isEqualTo("Content");
    }

    @Test
    void timeout_is_a_retryable_failure() {
        when(template.sendAndReceive(eq(""), eq(CommentCreatedRabbitTransport.QUEUE), any(Message.class)))
                .thenReturn(null);
        CommentCreatedRabbitTransport transport = new CommentCreatedRabbitTransport(
                template, objectMapper, Duration.ofMillis(10));

        assertThatThrownBy(() -> transport.deliver(delivery()))
                .isInstanceOfSatisfying(CommentCreatedDeliveryException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(CommentCreatedDeliveryException.Code.TIMEOUT));
    }

    @Test
    void non_comment_intent_never_uses_the_comment_queue() {
        CommentCreatedRabbitTransport transport = new CommentCreatedRabbitTransport(
                template, objectMapper, Duration.ofSeconds(1));
        NotificationOutboxService.Delivery nonComment = new NotificationOutboxService.Delivery(
                1L, eventId.toString(), 101L, "mention", "Title", "Content", 7L, 8L, "post", 1);

        assertThatThrownBy(() -> transport.deliver(nonComment))
                .isInstanceOfSatisfying(CommentCreatedDeliveryException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(CommentCreatedDeliveryException.Code.INVALID_INTENT));
        verify(template, org.mockito.Mockito.never())
                .sendAndReceive(eq(""), eq(CommentCreatedRabbitTransport.QUEUE), any(Message.class));
    }

    private NotificationOutboxService.Delivery delivery() {
        return new NotificationOutboxService.Delivery(
                1L, eventId.toString(), 101L, "reply", "Title", "Content", 7L, 8L, "post", 1);
    }
}
