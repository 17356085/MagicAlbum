package com.example.demo.moderation.dto;

import com.example.demo.moderation.model.ReportReason;
import com.example.demo.moderation.model.ReportStatus;
import com.example.demo.moderation.model.ReportTargetType;

import java.time.Instant;

public class ReportResolutionResponse extends ReportResponse {

    private Long reviewerId;
    private Instant resolvedAt;

    public ReportResolutionResponse() {
    }

    public ReportResolutionResponse(Long id,
                                    Long reporterId,
                                    ReportTargetType targetType,
                                    Long targetId,
                                    ReportReason reason,
                                    String detail,
                                    ReportStatus status,
                                    Instant createdAt,
                                    Long reviewerId,
                                    Instant resolvedAt) {
        this(id, reporterId, targetType, targetId, reason, detail, status, createdAt, null, reviewerId, resolvedAt);
    }

    public ReportResolutionResponse(Long id,
                                    Long reporterId,
                                    ReportTargetType targetType,
                                    Long targetId,
                                    ReportReason reason,
                                    String detail,
                                    ReportStatus status,
                                    Instant createdAt,
                                    Long targetThreadId,
                                    Long reviewerId,
                                    Instant resolvedAt) {
        super(id, reporterId, targetType, targetId, reason, detail, status, createdAt, targetThreadId);
        this.reviewerId = reviewerId;
        this.resolvedAt = resolvedAt;
    }

    public Long getReviewerId() {
        return reviewerId;
    }

    public void setReviewerId(Long reviewerId) {
        this.reviewerId = reviewerId;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}
