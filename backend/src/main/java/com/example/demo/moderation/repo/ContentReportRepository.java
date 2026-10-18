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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select report from ContentReport report where report.id = :reportId")
    Optional<ContentReport> findByIdForUpdate(@Param("reportId") Long reportId);
}
