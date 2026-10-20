package com.example.demo.ai.summary.repo;

import com.example.demo.ai.summary.entity.SummaryTask;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SummaryTaskRepository extends JpaRepository<SummaryTask, String> {
    interface Identity { Long getActor(); Long getThreadId(); }
    @Query("SELECT t.triggeredBy AS actor,t.threadId AS threadId FROM SummaryTask t WHERE t.taskId=:id")
    Optional<Identity> identity(@Param("id") String id);

    Optional<SummaryTask> findByTriggeredByAndRequestKey(Long triggeredBy, String requestKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM SummaryTask t WHERE t.taskId=:id")
    Optional<SummaryTask> findForUpdate(@Param("id") String id);

    @Query("SELECT COUNT(t) FROM SummaryTask t WHERE t.triggeredBy=:actor AND t.status IN ('PENDING','PROCESSING')")
    long countOpen(@Param("actor") Long actor);

    @Query("""
            SELECT t.taskId FROM SummaryTask t WHERE t.threadId=:thread AND t.contentVersion<:version
              AND t.status IN ('PENDING','PROCESSING')
            """)
    List<String> findObsoleteActiveIds(@Param("thread") Long threadId, @Param("version") long version);

    @Modifying
    @Query("""
            UPDATE SummaryTask t SET t.status='SUPERSEDED',t.errorCode='CONTENT_CHANGED',t.summary=NULL,
              t.leaseOwner=NULL,t.leaseUntil=NULL,t.updatedAt=:now
            WHERE t.threadId=:thread AND t.contentVersion<:version AND t.status IN ('PENDING','PROCESSING')
            """)
    int supersede(@Param("thread") Long threadId, @Param("version") long version, @Param("now") Instant now);

    @Query("""
            SELECT t.taskId FROM SummaryTask t WHERE t.taskId>:after AND
              ((t.status='PENDING' AND t.availableAt<=:now AND t.updatedAt<=:before)
               OR (t.status='PROCESSING' AND t.leaseUntil<=:now)) ORDER BY t.taskId
            """)
    List<String> recoverable(@Param("after") String after, @Param("now") Instant now,
                             @Param("before") Instant before, Pageable pageable);
}
