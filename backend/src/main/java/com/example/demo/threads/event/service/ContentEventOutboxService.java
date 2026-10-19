package com.example.demo.threads.event.service;

import com.example.demo.threads.event.config.ContentEventProperties;
import com.example.demo.threads.event.dto.ContentEventMessage;
import com.example.demo.threads.event.entity.ContentEventOutbox;
import com.example.demo.threads.event.entity.ContentEventStatus;
import com.example.demo.threads.event.repo.ContentEventOutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the database side of content event delivery. Network I/O is deliberately
 * outside these short claim and completion transactions.
 */
@Service
public class ContentEventOutboxService {
    private final ContentEventOutboxRepository repository;
    private final Clock clock;
    private final Duration leaseDuration;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration backoffBase;

    @Autowired
    public ContentEventOutboxService(ContentEventOutboxRepository repository,
                                     ContentEventProperties properties) {
        this(repository, properties, Clock.systemUTC());
    }

    public ContentEventOutboxService(ContentEventOutboxRepository repository,
                                     ContentEventProperties properties,
                                     Clock clock) {
        properties.validate();
        this.repository = repository;
        this.clock = clock;
        this.leaseDuration = properties.getLeaseDuration();
        this.batchSize = properties.getBatchSize();
        this.maxAttempts = properties.getMaxAttempts();
        this.backoffBase = properties.getBackoffBase();
    }

    @Transactional
    public Optional<Delivery> claimOne() {
        return claimOne(UUID.randomUUID().toString());
    }

    @Transactional
    public Optional<Delivery> claimOne(String owner) {
        return claim(owner, 1).stream().findFirst();
    }

    @Transactional
    public List<Delivery> claimBatch() {
        return claimBatch(UUID.randomUUID().toString());
    }

    @Transactional
    public List<Delivery> claimBatch(String owner) {
        return claim(owner, batchSize);
    }

    public int batchSize() {
        return batchSize;
    }

    @Transactional
    public boolean markPublished(Delivery delivery) {
        if (delivery == null) {
            return false;
        }
        return markPublished(delivery.id(), delivery.owner());
    }

    @Transactional
    public boolean markPublished(Long id, String owner) {
        return repository.markPublished(
                id,
                ContentEventStatus.IN_FLIGHT,
                ContentEventStatus.PUBLISHED,
                normalizeOwner(owner),
                clock.instant()
        ) == 1;
    }

    @Transactional
    public boolean markFailure(Delivery delivery, Throwable failure) {
        if (delivery == null) {
            return false;
        }
        return markFailure(delivery.id(), delivery.owner(), delivery.attempts(), failure);
    }

    @Transactional
    public boolean markFailure(Long id, String owner, Throwable failure) {
        Optional<ContentEventOutbox> row = id == null ? Optional.empty() : repository.findById(id);
        return row.map(event -> markFailure(id, owner, event.getAttempts(), failure)).orElse(false);
    }

    @Transactional
    public boolean markFailure(Long id, String owner, int attempts, Throwable failure) {
        Instant now = clock.instant();
        ContentEventStatus nextStatus = attempts >= maxAttempts
                ? ContentEventStatus.FAILED
                : ContentEventStatus.PENDING;
        Instant availableAt = nextStatus == ContentEventStatus.FAILED
                ? now
                : now.plus(backoff(attempts));
        return repository.markFailure(
                id,
                ContentEventStatus.IN_FLIGHT,
                nextStatus,
                availableAt,
                failureCode(failure),
                normalizeOwner(owner),
                now
        ) == 1;
    }

    @Transactional
    public boolean manualRetry(Long id) {
        if (id == null) {
            return false;
        }
        return repository.retryFailed(
                id,
                ContentEventStatus.FAILED,
                ContentEventStatus.PENDING,
                clock.instant()
        ) == 1;
    }

    public Optional<ContentEventOutbox> find(Long id) {
        return repository.findById(id);
    }

    public long count(ContentEventStatus status) {
        return repository.countByStatus(status);
    }

    public Diagnostics getDiagnostics() {
        Instant now = clock.instant();
        Map<ContentEventStatus, Long> counts = new EnumMap<>(ContentEventStatus.class);
        for (ContentEventStatus status : ContentEventStatus.values()) {
            counts.put(status, repository.countByStatus(status));
        }
        long oldestPendingAgeSeconds = repository
                .findFirstByStatusOrderByAvailableAtAscIdAsc(ContentEventStatus.PENDING)
                .map(row -> ageSeconds(row.getAvailableAt(), now))
                .orElse(0L);
        return new Diagnostics(Map.copyOf(counts), oldestPendingAgeSeconds);
    }

    private List<Delivery> claim(String owner, int limit) {
        String normalizedOwner = normalizeOwner(owner);
        Instant now = clock.instant();
        List<ContentEventOutbox> candidates = new ArrayList<>();
        candidates.addAll(repository.findTop20ByStatusAndAvailableAtLessThanEqualOrderByAvailableAtAscIdAsc(
                ContentEventStatus.PENDING, now));
        candidates.addAll(repository.findTop20ByStatusAndLeaseUntilLessThanOrderByLeaseUntilAscIdAsc(
                ContentEventStatus.IN_FLIGHT, now));
        candidates.sort(Comparator
                .comparing(ContentEventOutboxService::candidateTime,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ContentEventOutbox::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())));

        Map<Long, Delivery> claimed = new LinkedHashMap<>();
        int candidateLimit = Math.min(20, candidates.size());
        Instant leaseUntil = now.plus(leaseDuration);
        for (int index = 0; index < candidateLimit && claimed.size() < limit; index++) {
            ContentEventOutbox candidate = candidates.get(index);
            if (candidate.getId() == null) {
                continue;
            }
            if (candidate.getStatus() == ContentEventStatus.IN_FLIGHT
                    && candidate.getAttempts() >= maxAttempts) {
                repository.failExhaustedLease(
                        candidate.getId(),
                        ContentEventStatus.IN_FLIGHT,
                        ContentEventStatus.FAILED,
                        now,
                        maxAttempts,
                        "MAX_ATTEMPTS");
                continue;
            }
            int updated = repository.claim(
                    candidate.getId(),
                    ContentEventStatus.PENDING,
                    ContentEventStatus.IN_FLIGHT,
                    now,
                    normalizedOwner,
                    leaseUntil
            );
            if (updated == 1) {
                claimed.put(candidate.getId(), new Delivery(
                        candidate.getId(),
                        toMessage(candidate),
                        candidate.getAttempts() + 1,
                        normalizedOwner,
                        leaseUntil
                ));
            }
        }
        return List.copyOf(claimed.values());
    }

    private static Instant candidateTime(ContentEventOutbox row) {
        return row.getStatus() == ContentEventStatus.IN_FLIGHT
                ? row.getLeaseUntil()
                : row.getAvailableAt();
    }

    private static ContentEventMessage toMessage(ContentEventOutbox event) {
        return new ContentEventMessage(
                event.getEventId(),
                event.getSchemaVersion(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getAggregateVersion(),
                event.getEventType().name(),
                event.getOccurredAt(),
                event.getRequestId()
        );
    }

    private Duration backoff(int attempts) {
        int exponent = Math.min(Math.max(0, attempts - 1), 10);
        long multiplier = 1L << exponent;
        try {
            Duration computed = backoffBase.multipliedBy(multiplier);
            return computed.compareTo(Duration.ofSeconds(60)) > 0
                    ? Duration.ofSeconds(60)
                    : computed;
        } catch (ArithmeticException overflow) {
            return Duration.ofSeconds(60);
        }
    }

    private static long ageSeconds(Instant availableAt, Instant now) {
        if (availableAt == null || availableAt.isAfter(now)) {
            return 0L;
        }
        return Math.max(0L, Duration.between(availableAt, now).getSeconds());
    }

    private static String normalizeOwner(String owner) {
        if (owner == null || owner.isBlank() || owner.length() > 64) {
            throw new IllegalArgumentException("owner must be a non-empty value up to 64 characters");
        }
        return owner;
    }

    private static String failureCode(Throwable failure) {
        if (failure instanceof ContentEventPublisherTransport.PublishException publishException) {
            return publishException.code().name();
        }
        if (failure == null) {
            return "UNKNOWN";
        }
        String type = failure.getClass().getSimpleName();
        return type.isBlank() ? "DELIVERY_FAILED" : type.substring(0, Math.min(type.length(), 64));
    }

    public record Delivery(
            Long id,
            ContentEventMessage message,
            int attempts,
            String owner,
            Instant leaseUntil
    ) {
        public String eventId() {
            return message.eventId();
        }
    }

    public record Diagnostics(Map<ContentEventStatus, Long> counts, long oldestPendingAgeSeconds) {
    }
}
