package com.example.demo.threads.event.service;

import com.example.demo.threads.event.config.ContentEventProperties;
import com.example.demo.threads.event.dto.ContentEventMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Rabbit transport for content events. It deliberately owns its template so a
 * consumer or another publisher cannot change this publisher's mandatory and
 * confirmation settings through a shared bean.
 */
@Component
@ConditionalOnProperty(name = "app.content.events.enabled", havingValue = "true")
public class ContentEventPublisherTransport {
    public static final String EXCHANGE = "content.events.v1";
    public static final String ROUTING_KEY = "thread.changed";

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final Duration confirmTimeout;

    @Autowired
    public ContentEventPublisherTransport(
            ConnectionFactory connectionFactory,
            ObjectMapper objectMapper,
            @Value("${spring.rabbitmq.publisher-confirm-type:}") String confirmType,
            @Value("${spring.rabbitmq.publisher-returns:false}") boolean publisherReturns,
            ContentEventProperties properties
    ) {
        this(new RabbitTemplate(connectionFactory), objectMapper, properties.getConfirmTimeout());
        requirePublisherSettings(confirmType, publisherReturns);
        if (!(connectionFactory instanceof CachingConnectionFactory caching)
                || !caching.isPublisherConfirms() || caching.isSimplePublisherConfirms()
                || !caching.isPublisherReturns()) {
            throw new IllegalStateException("Content publisher requires correlated confirms and returns on its connection factory");
        }
        long connectionMillis = properties.getConnectionTimeout().toMillis();
        if (connectionMillis < 1 || connectionMillis > 60_000
                || caching.getRabbitConnectionFactory().getConnectionTimeout() <= 0
                || caching.getRabbitConnectionFactory().getConnectionTimeout() > connectionMillis) {
            throw new IllegalStateException("RabbitMQ connection timeout exceeds the content publisher lease budget");
        }
        caching.getRabbitConnectionFactory().setHandshakeTimeout((int) connectionMillis);
        caching.getRabbitConnectionFactory().setChannelRpcTimeout((int) connectionMillis);
    }

    /**
     * Convenience constructor for isolated tests. Spring-managed construction
     * uses the overload above so startup properties are checked.
     */
    public ContentEventPublisherTransport(ConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        this(new RabbitTemplate(connectionFactory), objectMapper, Duration.ofSeconds(5));
    }

    public ContentEventPublisherTransport(RabbitTemplate rabbitTemplate,
                                          ObjectMapper objectMapper,
                                          Duration confirmTimeout) {
        if (rabbitTemplate == null || objectMapper == null) {
            throw new IllegalArgumentException("Rabbit transport dependencies are required");
        }
        if (confirmTimeout == null || confirmTimeout.isZero() || confirmTimeout.isNegative()) {
            throw new IllegalArgumentException("confirmTimeout must be positive");
        }
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.confirmTimeout = confirmTimeout;
        rabbitTemplate.setMandatory(true);
        // Register the returns path so CorrelationData.getReturned() is populated.
        rabbitTemplate.setReturnsCallback(returned -> { });
    }

    public void publish(ContentEventMessage event) {
        if (event == null || event.eventId() == null || event.eventId().isBlank()) {
            throw new PublishException(Code.SERIALIZATION);
        }
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(event);
        } catch (JsonProcessingException exception) {
            throw new PublishException(Code.SERIALIZATION);
        } catch (RuntimeException exception) {
            throw new PublishException(Code.TRANSPORT);
        }

        publishBytes(EXCHANGE, ROUTING_KEY, payload, event.eventId());
    }

    /** Isolate poison messages without copying their untrusted body or credentials. */
    public void quarantine(byte[] original) {
        byte[] body = original == null ? new byte[0] : original;
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(body);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new PublishException(Code.SERIALIZATION);
        }
        String identity = UUID.nameUUIDFromBytes(digest).toString();
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("quarantineId", identity);
        diagnostic.put("bodySha256", HexFormat.of().formatHex(digest));
        diagnostic.put("reason", "INVALID_ENVELOPE");
        diagnostic.put("observedAt", Instant.now().toString());
        if (body.length <= 8192) {
            try {
                var source = objectMapper.readTree(body);
                if (source != null && source.isObject()) {
                    for (String field : new String[]{"aggregateId", "aggregateVersion", "schemaVersion"}) {
                        var value = source.get(field);
                        if (value != null && value.isIntegralNumber() && value.canConvertToLong()
                                && value.asLong() >= 0) {
                            diagnostic.put(field, value.asLong());
                        }
                    }
                    var eventId = source.get("eventId");
                    if (eventId != null && eventId.isTextual()
                            && eventId.textValue().matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
                        diagnostic.put("eventId", eventId.textValue());
                    }
                }
            } catch (IOException ignored) {
                // A digest still identifies a malformed envelope; no raw body is retained.
            }
        }
        try {
            publishBytes("content.events.dlx", "quarantined", objectMapper.writeValueAsBytes(diagnostic), identity);
        } catch (JsonProcessingException failure) {
            throw new PublishException(Code.SERIALIZATION);
        }
    }

    private void publishBytes(String exchange, String routingKey, byte[] payload, String messageId) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(messageId);
        Message message = new Message(payload, properties);
        CorrelationData correlationData = new CorrelationData(
                UUID.randomUUID() + ":" + messageId);
        try {
            rabbitTemplate.send(exchange, routingKey, message, correlationData);
            long timeoutMillis = Math.max(1L, confirmTimeout.toMillis());
            var confirm = correlationData.getFuture().get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (confirm == null || !confirm.ack()) {
                throw new PublishException(Code.CONFIRM_NACK);
            }
            if (correlationData.getReturned() != null) {
                throw new PublishException(Code.RETURNED);
            }
        } catch (PublishException exception) {
            throw exception;
        } catch (TimeoutException exception) {
            throw new PublishException(Code.CONFIRM_TIMEOUT);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PublishException(Code.CONFIRM_TIMEOUT);
        } catch (ExecutionException | RuntimeException exception) {
            throw new PublishException(Code.TRANSPORT);
        }
    }

    private static void requirePublisherSettings(String confirmType, boolean publisherReturns) {
        if (!"correlated".equalsIgnoreCase(confirmType)) {
            throw new IllegalStateException("spring.rabbitmq.publisher-confirm-type must be correlated");
        }
        if (!publisherReturns) {
            throw new IllegalStateException("spring.rabbitmq.publisher-returns must be true");
        }
    }

    public enum Code {
        SERIALIZATION,
        CONFIRM_NACK,
        CONFIRM_TIMEOUT,
        RETURNED,
        TRANSPORT
    }

    public static final class PublishException extends RuntimeException {
        private final Code code;

        public PublishException(Code code) {
            super(code == null ? Code.TRANSPORT.name() : code.name());
            this.code = code == null ? Code.TRANSPORT : code;
        }

        public Code code() {
            return code;
        }
    }

}
