package com.example.notification.messaging;

import com.example.notification.dto.CreateNotificationRequest;
import com.example.notification.dto.NotificationDto;
import com.example.notification.service.PersistentNotificationService;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

class CommentCreatedMessageListenerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PersistentNotificationService notificationService = mock(PersistentNotificationService.class);
    private final Channel channel = mock(Channel.class);
    private final CommentCreatedMessageListener listener =
            new CommentCreatedMessageListener(notificationService, objectMapper);
    private final UUID eventId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void valid_envelope_is_persisted_before_ack_and_returns_normal_notification_dto() throws Exception {
        NotificationDto dto = new NotificationDto(
                900L, "reply", "Title", "Content", false,
                null, 7L, 8L, "post");
        when(notificationService.create(any(CreateNotificationRequest.class))).thenAnswer(invocation -> {
            CreateNotificationRequest request = invocation.getArgument(0);
            assertThat(request.getUserId()).isEqualTo(101L);
            assertThat(request.getType()).isEqualTo("reply");
            assertThat(request.getTitle()).isEqualTo("Title");
            assertThat(request.getContent()).isEqualTo("Content");
            assertThat(request.getThreadId()).isEqualTo(7L);
            assertThat(request.getTargetId()).isEqualTo(8L);
            assertThat(request.getTargetType()).isEqualTo("post");
            assertThat(request.getEventId()).isEqualTo(eventId);
            return dto;
        });

        Message reply = listener.onMessage(message(event()), channel);

        assertThat(objectMapper.readValue(reply.getBody(), NotificationDto.class).getId()).isEqualTo(900L);
        assertThat(reply.getMessageProperties().getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(reply.getMessageProperties().getCorrelationId()).isEqualTo(eventId.toString());
        InOrder order = inOrder(notificationService, channel);
        order.verify(notificationService).create(any(CreateNotificationRequest.class));
        order.verify(channel).basicAck(11L, false);
    }

    @Test
    void malformed_envelope_is_dead_lettered_without_calling_persistence() throws Exception {
        listener.onMessage(message("{}"), channel);

        verify(channel).basicNack(11L, false, false);
        verifyNoInteractions(notificationService);
    }

    @Test
    void persistence_failure_is_not_requeued_into_a_hot_loop() throws Exception {
        doThrow(new IllegalStateException("database detail must not be logged"))
                .when(notificationService).create(any(CreateNotificationRequest.class));

        listener.onMessage(message(event()), channel);

        verify(channel).basicNack(11L, false, false);
    }

    private CommentCreatedEnvelope event() {
        return new CommentCreatedEnvelope(
                eventId.toString(), 1, "COMMENT_CREATED", 8L, 7L, 101L,
                "reply", "post", "Title", "Content");
    }

    private Message message(CommentCreatedEnvelope event) throws Exception {
        return message(objectMapper.writeValueAsBytes(event));
    }

    private Message message(String body) {
        return message(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private Message message(byte[] body) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(11L);
        properties.setCorrelationId(eventId.toString());
        properties.setReplyTo("amq.rabbitmq.reply-to");
        return new Message(body, properties);
    }
}
