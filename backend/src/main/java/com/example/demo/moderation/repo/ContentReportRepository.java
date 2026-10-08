package com.example.demo.moderation.repo;

import com.example.demo.moderation.entity.ContentReport;
import com.example.demo.moderation.entity.ReportStatus;
import com.example.demo.moderation.entity.ReportTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ContentReportRepository extends JpaRepository<ContentReport, Long> {

    Optional<ContentReport> findByReporterIdAndTargetTypeAndTargetId(
            Long reporterId,
            ReportTargetType targetType,
            Long targetId
    );

    Page<ContentReport> findByStatus(ReportStatus status, Pageable pageable);
}
