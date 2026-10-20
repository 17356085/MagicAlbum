package com.example.demo.ai.summary.repo;

import com.example.demo.ai.summary.entity.SummaryAttempt;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import java.time.Instant;
import org.springframework.data.repository.query.Param;

public interface SummaryAttemptRepository extends JpaRepository<SummaryAttempt, Long> {
    long countByTaskIdAndUsageEstimatedTrue(String taskId);
    @Modifying
    @Query("""
            UPDATE SummaryAttempt a SET a.status=:status,a.errorCode=:error,a.finishedAt=:now
            WHERE a.taskId=:task AND a.status='PROCESSING'
            """)
    int closeActive(@Param("task") String taskId, @Param("status") String status,
                    @Param("error") String error, @Param("now") Instant now);
    Optional<SummaryAttempt> findByTaskIdAndAttemptNumber(String taskId, int attemptNumber);
    @Query("SELECT COALESCE(SUM(a.chargedTokens),0) FROM SummaryAttempt a WHERE a.taskId=:task")
    long charged(@Param("task") String taskId);
}
