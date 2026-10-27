package com.example.demo.notifications.outbox;

import com.example.demo.user.service.UserSettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class NotificationOutboxService {
    private final NotificationOutboxRepository repository;
    private final UserSettingsService userSettingsService;
    private final Clock clock;
    private final Duration leaseDuration;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration backoffBase;
    private final Duration retention;

    @Autowired
    public NotificationOutboxService(
            NotificationOutboxRepository repository,
            UserSettingsService userSettingsService,
            @Value("${app.notifications.outbox.lease-duration:30s}") Duration leaseDuration,
            @Value("${app.notifications.outbox.batch-size:50}") int batchSize,
            @Value("${app.notifications.outbox.max-attempts:5}") int maxAttempts,
            @Value("${app.notifications.outbox.backoff-base:2s}") Duration backoffBase,
            @Value("${app.notifications.outbox.retention:7d}") Duration retention
    ) {
        this(repository, userSettingsService, Clock.systemUTC(), leaseDuration, batchSize, maxAttempts, backoffBase, retention);
        // Remote client configuration permits at most 10s connection + 10s read.
        if (leaseDuration.compareTo(Duration.ofSeconds(30)) < 0) {
            throw new IllegalArgumentException("Outbox lease requires at least 30 seconds including database commit margin");
        }
    }

    NotificationOutboxService(
            NotificationOutboxRepository repository,
            UserSettingsService userSettingsService,
            Clock clock,
            Duration leaseDuration,
            int batchSize,
            int maxAttempts,
            Duration backoffBase,
            Duration retention
    ) {
        this.repository = repository;
        this.userSettingsService = userSettingsService;
        this.clock = clock;
        this.leaseDuration = requirePositive(leaseDuration, "leaseDuration");
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("Outbox max attempts must be between 1 and 100");
        }
        this.maxAttempts = maxAttempts;
        this.backoffBase = requirePositive(backoffBase, "backoffBase");
        this.retention = requirePositive(retention, "retention");
    }

    /**
     * Enqueue is deliberately part of the caller's transaction. A missing
     * transaction is a programming error because the primary write and this
     * durable queue row must commit or roll back together.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<NotificationOutbox> enqueue(
            Long recipientUserId,
            String type,
            String title,
            String content,
            Long threadId,
            Long targetId,
            String targetType
    ) {
        return enqueue(UUID.randomUUID(), recipientUserId, type, title, content, threadId, targetId, targetType);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<NotificationOutbox> enqueue(
            UUID eventId,
            Long recipientUserId,
            String type,
            String title,
            String content,
            Long threadId,
            Long targetId,
            String targetType
    ) {
        if (recipientUserId == null || eventId == null) {
            return Optional.empty();
        }
        if (!userSettingsService.shouldDeliver(recipientUserId, type)) {
            // Preference-off events do not enter the queue. SUPPRESSED is
            // reserved for a preference that changes after enqueue.
            return Optional.empty();
        }

        Instant now = clock.instant();
        NotificationOutbox outbox = new NotificationOutbox();
        outbox.setEventId(eventId.toString());
        outbox.setRequestId(normalizeRequestId(MDC.get("requestId")));
        outbox.setRecipientUserId(recipientUserId);
        outbox.setType(normalize(type, "system"));
        outbox.setTitle(normalize(title, "你有一条新通知"));
        outbox.setContent(normalize(content, ""));
        outbox.setThreadId(threadId);
        outbox.setTargetId(targetId);
        outbox.setTargetType(normalizeNullable(targetType));
        outbox.setStatus(NotificationOutboxStatus.PENDING);
        outbox.setAttempts(0);
        outbox.setAvailableAt(now);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        return Optional.of(repository.save(outbox));
    }

    @Transactional
    public List<Delivery> claimBatch(String owner) {
        return claim(owner, batchSize);
    }

    @Transactional
    public Optional<Delivery> claimOne(String owner) {
        return claim(owner, 1).stream().findFirst();
    }

    public int batchSize() {
        return batchSize;
    }

    private List<Delivery> claim(String owner, int limit) {
        Instant now = clock.instant();
        int pageSize = Math.min(limit, 100);
        Set<Long> attemptedIds = new HashSet<>();
        Map<Long, Delivery> claimed = new LinkedHashMap<>();
        while (true) {
            boolean widen = false;
            for (NotificationOutbox candidate : readCandidates(now, pageSize)) {
                if (claimed.size() >= limit || candidate.getId() == null) {
                    break;
                }
                if (!attemptedIds.add(candidate.getId())) {
                    continue;
                }
                // A late committed lease may already be expired; match the attempt snapshot too.
                int expectedAttempts = candidate.getAttempts();
                Delivery delivery = Delivery.from(candidate, expectedAttempts + 1);
                int updated = repository.claim(
                        candidate.getId(),
                        NotificationOutboxStatus.PENDING,
                        NotificationOutboxStatus.IN_FLIGHT,
                        now,
                        owner,
                        now.plus(leaseDuration),
                        expectedAttempts
                );
                if (updated == 1) {
                    claimed.put(candidate.getId(), delivery);
                } else if (pageSize < 100) {
                    // A short first window can hide an older row behind a lost head;
                    // widen once, keep the same cutoff, and skip every attempted id.
                    widen = true;
                    break;
                }
            }
            if (!widen) {
                break;
            }
            pageSize = 100;
        }
        return List.copyOf(claimed.values());
    }

    private List<NotificationOutbox> readCandidates(Instant now, int pageSize) {
        List<NotificationOutbox> candidates = new ArrayList<>();
        candidates.addAll(repository.findByStatusAndAvailableAtLessThanEqualOrderByAvailableAtAscIdAsc(
                NotificationOutboxStatus.PENDING,
                now,
                PageRequest.of(0, pageSize)
        ));
        candidates.addAll(repository.findByStatusAndLeaseUntilLessThanOrderByLeaseUntilAscIdAsc(
                NotificationOutboxStatus.IN_FLIGHT,
                now,
                PageRequest.of(0, pageSize)
        ));
        // Old expired leases remain eligible even while new pending events keep arriving.
        candidates.sort(Comparator.comparing((NotificationOutbox row) ->
                row.getStatus() == NotificationOutboxStatus.IN_FLIGHT ? row.getLeaseUntil() : row.getAvailableAt())
                .thenComparing(NotificationOutbox::getId));
        return candidates;
    }

    @Transactional
    public boolean markDelivered(Long id, String owner) {
        return repository.markDelivered(
                id,
                NotificationOutboxStatus.IN_FLIGHT,
                NotificationOutboxStatus.DELIVERED,
                owner,
                clock.instant()
        ) == 1;
    }

    @Transactional
    public boolean markSuppressed(Long id, String owner, String reason) {
        Instant now = clock.instant();
        return repository.finishFailure(
                id,
                NotificationOutboxStatus.IN_FLIGHT,
                NotificationOutboxStatus.SUPPRESSED,
                now,
                truncate(reason),
                owner,
                now
        ) == 1;
    }

    @Transactional
    public boolean markFailedOrRetry(Delivery delivery, String owner, Throwable failure) {
        Instant now = clock.instant();
        int attempts = delivery.attempts();
        NotificationOutboxStatus nextStatus = attempts >= maxAttempts || isPermanentFailure(failure)
                ? NotificationOutboxStatus.FAILED
                : NotificationOutboxStatus.PENDING;
        Instant availableAt = nextStatus == NotificationOutboxStatus.FAILED
                ? now
                : now.plus(backoff(attempts));
        return repository.finishFailure(
                delivery.id(),
                NotificationOutboxStatus.IN_FLIGHT,
                nextStatus,
                availableAt,
                failureCode(failure),
                owner,
                now
        ) == 1;
    }

    @Transactional
    public boolean manualRetry(Long id) {
        return repository.retryFailed(
                id,
                NotificationOutboxStatus.FAILED,
                NotificationOutboxStatus.PENDING,
                clock.instant()
        ) == 1;
    }

    @Transactional
    public int cleanupTerminal() {
        return repository.deleteTerminal(
                NotificationOutboxStatus.DELIVERED,
                NotificationOutboxStatus.SUPPRESSED,
                clock.instant().minus(retention)
        );
    }

    public Optional<NotificationOutbox> find(Long id) {
        return repository.findById(id);
    }

    public long count(NotificationOutboxStatus status) {
        return repository.countByStatus(status);
    }

    public long oldestPendingAgeSeconds() {
        return repository.findFirstByStatusOrderByCreatedAtAsc(NotificationOutboxStatus.PENDING)
                .map(row -> {
                    Instant createdAt = row.getCreatedAt();
                    if (createdAt == null) {
                        return 0L;
                    }
                    return Math.max(0L, Duration.between(createdAt, clock.instant()).getSeconds());
                })
                .orElse(0L);
    }

    private static boolean isPermanentFailure(Throwable failure) {
        if (failure instanceof ResponseStatusException response) {
            int status = response.getStatusCode().value();
            return status >= 400 && status < 500 && status != 408 && status != 429;
        }
        return false;
    }

    private static String failureCode(Throwable failure) {
        if (failure == null) return "delivery_failed";
        if (failure instanceof ResponseStatusException response) {
            return "HTTP_" + response.getStatusCode().value();
        }
        // Never persist arbitrary downstream messages, tokens, URLs or notification content.
        return failure.getClass().getSimpleName();
    }

    private Duration backoff(int attempts) {
        int exponent = Math.min(Math.max(0, attempts - 1), 10);
        long multiplier = 1L << exponent;
        try {
            Duration computed = backoffBase.multipliedBy(multiplier);
            return computed.compareTo(Duration.ofDays(1)) > 0 ? Duration.ofDays(1) : computed;
        } catch (ArithmeticException overflow) {
            return Duration.ofDays(1);
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeRequestId(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || !value.matches("[A-Za-z0-9._:-]+")) {
            return null;
        }
        return value;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    public record Delivery(
            Long id,
            String eventId,
            Long recipientUserId,
            String type,
            String title,
            String content,
            Long threadId,
            Long targetId,
            String targetType,
            int attempts,
            String requestId
    ) {
        public Delivery(
                Long id,
                String eventId,
                Long recipientUserId,
                String type,
                String title,
                String content,
                Long threadId,
                Long targetId,
                String targetType,
                int attempts
        ) {
            this(id, eventId, recipientUserId, type, title, content, threadId, targetId, targetType, attempts, null);
        }

        static Delivery from(NotificationOutbox outbox, int attempts) {
            return new Delivery(
                    outbox.getId(),
                    outbox.getEventId(),
                    outbox.getRecipientUserId(),
                    outbox.getType(),
                    outbox.getTitle(),
                    outbox.getContent(),
                    outbox.getThreadId(),
                    outbox.getTargetId(),
                    outbox.getTargetType(),
                    attempts,
                    outbox.getRequestId()
            );
        }
    }
}
