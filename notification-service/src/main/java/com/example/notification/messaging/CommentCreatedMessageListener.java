package com.example.notification.messaging;

import com.example.notification.dto.NotificationDto;
import com.example.notification.service.PersistentNotificationService;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Receives comment-created RPC requests and replies only after the persistent
 * notification service has completed its idempotent write transaction.
 */
@Component
@ConditionalOnProperty(name = "app.notifications.comment-events.enabled", havingValue = "true")
public class CommentCreatedMessageListener {
    private static final Logger log = LoggerFactory.getLogger(CommentCreatedMessageListener.class);

    private final PersistentNotificationService notificationService;
    private final ObjectMapper objectMapper;

    public CommentCreatedMessageListener(
            PersistentNotificationService notificationService,
            ObjectMapper objectMapper
    ) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(
            queues = CommentCreatedMessagingConstants.QUEUE,
            containerFactory = "commentCreatedListenerFactory"
    )
    public Message onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message == null || message.getMessageProperties() == null
                ? 0L
                : message.getMessageProperties().getDeliveryTag();
        CommentCreatedEnvelope event;
        try {
            event = decode(message);
            event.validate();
        } catch (RuntimeException invalid) {
            log.warn("comment_created_rejected code=INVALID_ENVELOPE");
            reject(channel, deliveryTag);
            return null;
        }

        try {
            NotificationDto notification = notificationService.create(event.toCreateRequest());
            Message reply = reply(message, notification);
            // Persistence has already committed before create returns. The
            // producer marks its outbox row delivered only after this reply.
            channel.basicAck(deliveryTag, false);
            return reply;
        } catch (RuntimeException | IOException failure) {
            // Do not requeue poison or dependency failures in a hot loop. The
            // core outbox retries the same event id after its bounded timeout.
            log.warn("comment_created_rejected code={}", failure.getClass().getSimpleName());
            reject(channel, deliveryTag);
            return null;
        }
    }

    private CommentCreatedEnvelope decode(Message message) {
        if (message == null || message.getBody() == null || message.getBody().length == 0) {
            throw new CommentCreatedMessageValidationException();
        }
        try {
            return objectMapper.readValue(message.getBody(), CommentCreatedEnvelope.class);
        } catch (RuntimeException invalid) {
            throw new CommentCreatedMessageValidationException();
        }
    }

    private Message reply(Message request, NotificationDto notification) {
        if (notification == null) {
            throw new CommentCreatedMessageValidationException();
        }
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT);
        String correlationId = request.getMessageProperties().getCorrelationId();
        if (correlationId != null && !correlationId.isBlank()) {
            properties.setCorrelationId(correlationId);
        }
        return new Message(objectMapper.writeValueAsBytes(notification), properties);
    }

    private void reject(Channel channel, long deliveryTag) throws IOException {
        if (channel != null && deliveryTag > 0) {
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
