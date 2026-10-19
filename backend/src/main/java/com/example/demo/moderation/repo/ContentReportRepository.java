package com.example.demo.moderation.repo;

import com.example.demo.moderation.entity.ContentReport;
import com.example.demo.moderation.model.ReportStatus;
import com.example.demo.moderation.model.ReportTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface ContentReportRepository extends JpaRepository<ContentReport, Long> {

    Optional<ContentReport> findByReporterIdAndTargetTypeAndTargetId(
            Long reporterId,
            ReportTargetType targetType,
            Long targetId
    );

    Page<ContentReport> findByStatus(ReportStatus status, Pageable pageable);

    @Query(value = """
            SELECT r.* FROM content_reports r
            LEFT JOIN posts p ON r.target_type='POST' AND p.id=r.target_id
            JOIN threads t ON t.id=CASE WHEN r.target_type='THREAD' THEN r.target_id ELSE p.thread_id END
            WHERE r.status=:status AND t.section_id IN (:sections)
            ORDER BY r.created_at DESC,r.id DESC
            """, countQuery = """
            SELECT COUNT(*) FROM content_reports r
            LEFT JOIN posts p ON r.target_type='POST' AND p.id=r.target_id
            JOIN threads t ON t.id=CASE WHEN r.target_type='THREAD' THEN r.target_id ELSE p.thread_id END
            WHERE r.status=:status AND t.section_id IN (:sections)
            """, nativeQuery = true)
    Page<ContentReport> findScoped(@Param("status") String status,
                                  @Param("sections") java.util.Collection<Long> sections, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select report from ContentReport report where report.id = :reportId")
    Optional<ContentReport> findByIdForUpdate(@Param("reportId") Long reportId);
}
