package com.example.demo.search.service;

import com.example.demo.search.port.ContentSearchIndex;
import com.example.demo.search.port.SearchVersionConflictException;
import com.example.demo.threads.event.dto.ContentEventMessage;
import com.example.demo.threads.service.ThreadSearchProjectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true")
public class ContentSearchConsumer {
    private static final Logger log = LoggerFactory.getLogger(ContentSearchConsumer.class);
    private static final Set<String> TYPES = Set.of("THREAD_UPSERTED", "THREAD_HIDDEN", "THREAD_DELETED");
    private final ThreadSearchProjectionService owner;
    private final ContentSearchIndex index;
    private final ObjectMapper mapper;
    private final int maxAttempts;
    private final long backoffMillis;

    public ContentSearchConsumer(ThreadSearchProjectionService owner, ContentSearchIndex index, ObjectMapper mapper,
                                 @Value("${app.search.consumer.max-attempts:3}") int maxAttempts,
                                 @Value("${app.search.consumer.backoff-ms:250}") long backoffMillis) {
        if (maxAttempts < 1 || maxAttempts > 5 || backoffMillis < 0 || backoffMillis > 2000) {
            throw new IllegalArgumentException("Invalid content consumer retry bounds");
        }
        this.owner = owner;
        this.index = index;
        this.mapper = mapper;
        this.maxAttempts = maxAttempts;
        this.backoffMillis = backoffMillis;
    }

    public ContentEventMessage decode(byte[] body) {
        if (body == null || body.length > 8192) {
            throw new InvalidContentEventException("INVALID_ENVELOPE_SIZE");
        }
        try {
            ContentEventMessage event = mapper.readValue(body, ContentEventMessage.class);
            validate(event);
            return event;
        } catch (IOException | IllegalArgumentException failure) {
            throw new InvalidContentEventException("INVALID_ENVELOPE");
        }
    }

    private void validate(ContentEventMessage event) {
        if (event == null || event.schemaVersion() != 1 || !"THREAD".equals(event.aggregateType())
                || event.aggregateId() <= 0 || event.aggregateVersion() <= 0 || event.occurredAt() == null
                || event.eventType() == null || !TYPES.contains(event.eventType()) || event.eventId() == null) {
            throw new InvalidContentEventException("UNSUPPORTED_ENVELOPE");
        }
        boolean validId;
        try {
            validId = UUID.fromString(event.eventId()).toString().equals(event.eventId());
        } catch (IllegalArgumentException invalidId) {
            validId = false;
        }
        if (!validId) {
            throw new InvalidContentEventException("INVALID_EVENT_ID");
        }
        if (event.requestId() != null && (event.requestId().length() > 128
                || !event.requestId().matches("[A-Za-z0-9._:-]+"))) {
            throw new InvalidContentEventException("INVALID_REQUEST_ID");
        }
    }

    public void apply(ContentEventMessage event) {
        validate(event);
        var document = owner.read(event.aggregateId())
                .orElseThrow(() -> new InvalidContentEventException("OWNER_MISSING"));
        if (document.version() < event.aggregateVersion()) {
            throw new IllegalStateException("OWNER_BEHIND_EVENT");
        }
        if (document.version() == event.aggregateVersion()
                && (!document.eventId().equals(event.eventId()) || !document.eventType().equals(event.eventType()))) {
            throw new InvalidContentEventException("OWNER_VERSION_CONFLICT");
        }
        // A newer owner projection keeps its own version and event provenance.
        index.put(ContentSearchIndex.ALIAS, document);
    }

    @RabbitListener(queues = "content.search.v1", containerFactory = "contentSearchListenerFactory")
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        ContentEventMessage event;
        try {
            event = decode(message.getBody());
        } catch (RuntimeException invalid) {
            log.warn("content_search_quarantined code=INVALID_ENVELOPE");
            channel.basicNack(deliveryTag, false, false);
            return;
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                apply(event);
                channel.basicAck(deliveryTag, false);
                return;
            } catch (InvalidContentEventException | SearchVersionConflictException conflict) {
                log.warn("content_search_quarantined eventId={} aggregateId={} version={} code=VERSION_OR_SCHEMA_CONFLICT",
                        event.eventId(), event.aggregateId(), event.aggregateVersion());
                channel.basicNack(deliveryTag, false, false);
                return;
            } catch (RuntimeException dependencyFailure) {
                if (attempt == maxAttempts) {
                    break;
                }
                try {
                    Thread.sleep(Math.min(backoffMillis * attempt, 2000));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.warn("content_search_quarantined eventId={} aggregateId={} version={} code=DEPENDENCY_UNAVAILABLE",
                event.eventId(), event.aggregateId(), event.aggregateVersion());
        channel.basicNack(deliveryTag, false, false);
    }
}
