package com.example.demo.threads.event.service;

import com.example.demo.threads.event.entity.ContentEventOutbox;
import com.example.demo.threads.event.entity.ContentEventStatus;
import com.example.demo.threads.event.entity.ContentEventType;
import com.example.demo.threads.event.repo.ContentEventOutboxRepository;
import com.example.demo.threads.maintenance.service.ContentWriteGateService;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class ContentEventRecorder {
    private static final int SCHEMA_VERSION = 1;
    private static final String AGGREGATE_TYPE = "THREAD";
    private static final String REQUEST_ID_PATTERN = "[A-Za-z0-9._:-]+";

    private final ContentEventOutboxRepository repository;
    private final ContentWriteGateService contentWriteGateService;
    private final Clock clock;

    @Autowired
    public ContentEventRecorder(ContentEventOutboxRepository repository,
                                ContentWriteGateService contentWriteGateService) {
        this(repository, contentWriteGateService, Clock.systemUTC());
    }

    ContentEventRecorder(ContentEventOutboxRepository repository,
                         ContentWriteGateService contentWriteGateService,
                         Clock clock) {
        this.repository = repository;
        this.contentWriteGateService = contentWriteGateService;
        this.clock = clock;
    }

    /**
     * Records the durable content event in the caller's transaction. A missing
     * transaction is a programming error because the thread write and event
     * row must commit or roll back together.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ContentEventOutbox record(Long aggregateId, long aggregateVersion, ContentEventType eventType) {
        return record(UUID.randomUUID(), aggregateId, aggregateVersion, eventType, MDC.get("requestId"));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ContentEventOutbox record(UUID eventId,
                                     Long aggregateId,
                                     long aggregateVersion,
                                     ContentEventType eventType,
                                     String requestId) {
        contentWriteGateService.enter();
        requirePositive(aggregateId, "aggregateId");
        if (aggregateVersion <= 0) {
            throw new IllegalArgumentException("aggregateVersion must be positive");
        }
        if (eventId == null || eventType == null) {
            throw new IllegalArgumentException("eventId and eventType are required");
        }

        ContentEventOutbox event = new ContentEventOutbox();
        event.setEventId(eventId.toString());
        event.setSchemaVersion(SCHEMA_VERSION);
        event.setAggregateType(AGGREGATE_TYPE);
        event.setAggregateId(aggregateId);
        event.setAggregateVersion(aggregateVersion);
        event.setEventType(eventType);
        Instant occurredAt = clock.instant();
        event.setOccurredAt(occurredAt);
        event.setRequestId(normalizeRequestId(requestId));
        event.setStatus(ContentEventStatus.PENDING);
        event.setAttempts(0);
        event.setAvailableAt(occurredAt);
        return repository.save(event);
    }

    static String normalizeRequestId(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || !value.matches(REQUEST_ID_PATTERN)) {
            return null;
        }
        return value;
    }

    private void requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
