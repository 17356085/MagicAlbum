package com.example.demo.search.service;

import com.example.demo.search.port.ContentSearchIndex;
import com.example.demo.threads.event.dto.ContentEventMessage;
import com.example.demo.threads.service.ThreadSearchProjectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.Optional;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true")
public class ContentSearchProjection {
    private static final Set<String> TYPES = Set.of("THREAD_UPSERTED", "THREAD_HIDDEN", "THREAD_DELETED");

    private final ThreadSearchProjectionService owner;
    private final ContentSearchIndex index;
    private final ContentSearchPipelineMetrics metrics;
    private final String rabbitIndex;

    public ContentSearchProjection(ThreadSearchProjectionService owner, ContentSearchIndex index,
                                   ContentSearchPipelineMetrics metrics) {
        this(owner, index, metrics, ContentSearchIndex.ALIAS);
    }

    @Autowired
    public ContentSearchProjection(ThreadSearchProjectionService owner, ContentSearchIndex index,
                                   ContentSearchPipelineMetrics metrics,
                                   Optional<ContentSearchPilotIndexes> pilotIndexes) {
        this(owner, index, metrics, pilotIndexes.map(ContentSearchPilotIndexes::rabbit)
                .orElse(ContentSearchIndex.ALIAS));
    }

    private ContentSearchProjection(ThreadSearchProjectionService owner, ContentSearchIndex index,
                                    ContentSearchPipelineMetrics metrics, String rabbitIndex) {
        this.owner = owner;
        this.index = index;
        this.metrics = metrics;
        this.rabbitIndex = rabbitIndex;
    }

    public void apply(ContentEventMessage event) {
        applyTo(rabbitIndex, event);
    }

    void applyTo(String target, ContentEventMessage event) {
        validate(event);
        if (!ContentSearchIndex.ALIAS.equals(target)) index.count(target);
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
        index.put(target, document);
        metrics.indexed(event.occurredAt());
    }

    static void validate(ContentEventMessage event) {
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
}
