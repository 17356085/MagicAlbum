package com.example.demo.notifications.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, Long> {
    Optional<NotificationOutbox> findByEventId(String eventId);

    long countByStatus(NotificationOutboxStatus status);

    List<NotificationOutbox> findTop100ByStatusAndAvailableAtLessThanEqualOrderByAvailableAtAscIdAsc(
            NotificationOutboxStatus status,
            Instant availableAt
    );

    List<NotificationOutbox> findTop100ByStatusAndLeaseUntilLessThanOrderByLeaseUntilAscIdAsc(
            NotificationOutboxStatus status,
            Instant leaseUntil
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationOutbox o
               set o.status = :inFlight,
                   o.leaseOwner = :owner,
                   o.leaseUntil = :leaseUntil,
                   o.attempts = o.attempts + 1,
                   o.updatedAt = :now
             where o.id = :id
               and ((o.status = :pending and o.availableAt <= :now)
                 or (o.status = :inFlight and o.leaseUntil < :now))
            """)
    int claim(
            @Param("id") Long id,
            @Param("pending") NotificationOutboxStatus pending,
            @Param("inFlight") NotificationOutboxStatus inFlight,
            @Param("now") Instant now,
            @Param("owner") String owner,
            @Param("leaseUntil") Instant leaseUntil
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationOutbox o
               set o.status = :delivered,
                   o.leaseOwner = null,
                   o.leaseUntil = null,
                   o.deliveredAt = :now,
                   o.updatedAt = :now
             where o.id = :id
               and o.status = :inFlight
               and o.leaseOwner = :owner
            """)
    int markDelivered(
            @Param("id") Long id,
            @Param("inFlight") NotificationOutboxStatus inFlight,
            @Param("delivered") NotificationOutboxStatus delivered,
            @Param("owner") String owner,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationOutbox o
               set o.status = :status,
                   o.availableAt = :availableAt,
                   o.leaseOwner = null,
                   o.leaseUntil = null,
                   o.lastError = :lastError,
                   o.updatedAt = :now
             where o.id = :id
               and o.status = :inFlight
               and o.leaseOwner = :owner
            """)
    int finishFailure(
            @Param("id") Long id,
            @Param("inFlight") NotificationOutboxStatus inFlight,
            @Param("status") NotificationOutboxStatus status,
            @Param("availableAt") Instant availableAt,
            @Param("lastError") String lastError,
            @Param("owner") String owner,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationOutbox o
               set o.status = :pending,
                   o.availableAt = :now,
                   o.attempts = 0,
                   o.leaseOwner = null,
                   o.leaseUntil = null,
                   o.lastError = null,
                   o.updatedAt = :now
             where o.id = :id
               and o.status = :failed
            """)
    int retryFailed(
            @Param("id") Long id,
            @Param("failed") NotificationOutboxStatus failed,
            @Param("pending") NotificationOutboxStatus pending,
            @Param("now") Instant now
    );

    @Modifying
    @Query("""
            delete from NotificationOutbox o
             where o.status in (:delivered, :suppressed)
               and o.updatedAt < :before
            """)
    int deleteTerminal(
            @Param("delivered") NotificationOutboxStatus delivered,
            @Param("suppressed") NotificationOutboxStatus suppressed,
            @Param("before") Instant before
    );
}
