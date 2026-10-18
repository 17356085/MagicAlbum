package com.example.demo.threads.repo;

import com.example.demo.threads.entity.Thread;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/** Read only durable summary intentions; claiming belongs to the consumer. */
public interface ThreadSummaryRecoveryRepository extends Repository<Thread, Long> {
    @Query("""
            SELECT t.id FROM Thread t
            WHERE t.id > :afterId
              AND t.status = 'NORMAL'
              AND (((t.summaryStatus = 'PENDING' OR t.summaryStatus IS NULL) AND t.createdAt <= :before)
                   OR (t.summaryStatus = 'PROCESSING' AND (t.summaryLeaseUntil IS NULL OR t.summaryLeaseUntil <= :now)))
            ORDER BY t.id
            """)
    List<Long> findPendingIds(@Param("afterId") long afterId,
                             @Param("before") Instant before,
                             @Param("now") Instant now,
                             Pageable pageable);
}
