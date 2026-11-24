package com.example.demo.notifications.service.rabbit;

import com.example.demo.notifications.dto.NotificationDto;
import com.example.demo.notifications.outbox.NotificationOutboxService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Synchronous Rabbit RPC transport for comment-created reply notifications.
 *
 * <p>The outbox remains the retry authority. A missing or invalid reply is a
 * delivery failure, so the caller keeps the same event id for its next retry.</p>
 */
@Component
@ConditionalOnProperty(name = "app.notifications.comment-events.enabled", havingValue = "true")
public class CommentCreatedRabbitTransport {
    public static final String QUEUE = "notification.comment-created.v1";

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public CommentCreatedRabbitTransport(
            ConnectionFactory connectionFactory,
            ObjectMapper objectMapper,
            @Value("${app.notifications.comment-events.reply-timeout:5s}") Duration replyTimeout
    ) {
        this(new RabbitTemplate(connectionFactory), objectMapper, replyTimeout);
    }

    /** Isolated constructor for transport and contract tests. */
    public CommentCreatedRabbitTransport(
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            Duration replyTimeout
    ) {
        if (rabbitTemplate == null || objectMapper == null) {
            throw new IllegalArgumentException("Rabbit transport dependencies are required");
        }
        if (replyTimeout == null || replyTimeout.isZero() || replyTimeout.isNegative()
                || replyTimeout.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("Comment event reply timeout must be between 1ms and 10s");
        }
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        rabbitTemplate.setReplyTimeout(replyTimeout.toMillis());
    }

    public NotificationDto deliver(NotificationOutboxService.Delivery delivery) {
        CommentCreatedEnvelope event = CommentCreatedEnvelope.from(delivery);
        Message request = toMessage(event);
        Message response;
        try {
            response = rabbitTemplate.sendAndReceive("", QUEUE, request);
        } catch (RuntimeException failure) {
            throw new CommentCreatedDeliveryException(CommentCreatedDeliveryException.Code.TRANSPORT, failure);
        }
        if (response == null) {
            throw new CommentCreatedDeliveryException(CommentCreatedDeliveryException.Code.TIMEOUT);
        }
        try {
            NotificationDto dto = objectMapper.readValue(response.getBody(), NotificationDto.class);
            if (dto == null) {
                throw new CommentCreatedDeliveryException(CommentCreatedDeliveryException.Code.INVALID_ACK);
            }
            return dto;
        } catch (CommentCreatedDeliveryException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new CommentCreatedDeliveryException(CommentCreatedDeliveryException.Code.INVALID_ACK, failure);
        }
    }

    private Message toMessage(CommentCreatedEnvelope event) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(event);
            MessageProperties properties = new MessageProperties();
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setContentEncoding(StandardCharsets.UTF_8.name());
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setMessageId(event.eventId());
            properties.setCorrelationId(event.eventId());
            return new Message(body, properties);
        } catch (RuntimeException failure) {
            throw new CommentCreatedDeliveryException(CommentCreatedDeliveryException.Code.SERIALIZATION, failure);
        }
    }
}
