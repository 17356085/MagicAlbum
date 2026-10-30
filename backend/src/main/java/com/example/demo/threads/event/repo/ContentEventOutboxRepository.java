package com.example.demo.threads.event.repo;

import com.example.demo.threads.event.entity.ContentEventOutbox;
import com.example.demo.threads.event.entity.ContentEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ContentEventOutboxRepository extends JpaRepository<ContentEventOutbox, Long> {
    Optional<ContentEventOutbox> findByEventId(String eventId);

    Optional<ContentEventOutbox> findByAggregateIdAndAggregateVersion(Long aggregateId, long aggregateVersion);

    List<ContentEventOutbox> findTop20ByStatusAndAvailableAtLessThanEqualOrderByAvailableAtAscIdAsc(
            ContentEventStatus status,
            Instant availableAt
    );

    List<ContentEventOutbox> findTop20ByStatusAndLeaseUntilLessThanOrderByLeaseUntilAscIdAsc(
            ContentEventStatus status,
            Instant leaseUntil
    );

    long countByStatus(ContentEventStatus status);

    Optional<ContentEventOutbox> findFirstByStatusOrderByAvailableAtAscIdAsc(ContentEventStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ContentEventOutbox outbox
               set outbox.status = :inFlight,
                   outbox.leaseOwner = :owner,
                   outbox.leaseUntil = :leaseUntil,
                   outbox.attempts = outbox.attempts + 1
             where outbox.id = :id
               and outbox.attempts = :expectedAttempts
               and ((outbox.status = :pending and outbox.availableAt <= :now)
                 or (outbox.status = :inFlight and outbox.leaseUntil < :now))
            """)
    int claim(
            @Param("id") Long id,
            @Param("pending") ContentEventStatus pending,
            @Param("inFlight") ContentEventStatus inFlight,
            @Param("now") Instant now,
            @Param("owner") String owner,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("expectedAttempts") int expectedAttempts
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ContentEventOutbox outbox
               set outbox.status = :published,
                   outbox.leaseOwner = null,
                   outbox.leaseUntil = null,
                   outbox.publishedAt = :now,
                   outbox.lastError = null
             where outbox.id = :id
               and outbox.status = :inFlight
               and outbox.leaseOwner = :owner
               and outbox.leaseUntil > :now
            """)
    int markPublished(
            @Param("id") Long id,
            @Param("inFlight") ContentEventStatus inFlight,
            @Param("published") ContentEventStatus published,
            @Param("owner") String owner,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ContentEventOutbox outbox
               set outbox.status = :status,
                   outbox.availableAt = :availableAt,
                   outbox.leaseOwner = null,
                   outbox.leaseUntil = null,
                   outbox.lastError = :lastError
             where outbox.id = :id
               and outbox.status = :inFlight
               and outbox.leaseOwner = :owner
               and outbox.leaseUntil > :now
            """)
    int markFailure(
            @Param("id") Long id,
            @Param("inFlight") ContentEventStatus inFlight,
            @Param("status") ContentEventStatus status,
            @Param("availableAt") Instant availableAt,
            @Param("lastError") String lastError,
            @Param("owner") String owner,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ContentEventOutbox outbox
               set outbox.status = :failed,
                   outbox.availableAt = :now,
                   outbox.leaseOwner = null,
                   outbox.leaseUntil = null,
                   outbox.lastError = :lastError
             where outbox.id = :id
               and outbox.status = :inFlight
               and outbox.leaseOwner is not null
               and outbox.leaseUntil < :now
               and outbox.attempts >= :maxAttempts
            """)
    int failExhaustedLease(
            @Param("id") Long id,
            @Param("inFlight") ContentEventStatus inFlight,
            @Param("failed") ContentEventStatus failed,
            @Param("now") Instant now,
            @Param("maxAttempts") int maxAttempts,
            @Param("lastError") String lastError
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ContentEventOutbox outbox
               set outbox.status = :pending,
                   outbox.availableAt = :now,
                   outbox.attempts = 0,
                   outbox.leaseOwner = null,
                   outbox.leaseUntil = null,
                   outbox.publishedAt = null,
                   outbox.lastError = null
             where outbox.id = :id
               and outbox.status = :failed
            """)
    int retryFailed(
            @Param("id") Long id,
            @Param("failed") ContentEventStatus failed,
            @Param("pending") ContentEventStatus pending,
            @Param("now") Instant now
    );
}
