package com.example.demo.moderation.dto;

import com.example.demo.moderation.model.ReportReason;
import com.example.demo.moderation.model.ReportStatus;
import com.example.demo.moderation.model.ReportTargetType;

import java.time.Instant;

public class ReportResponse {

    private Long id;
    private Long reporterId;
    private ReportTargetType targetType;
    private Long targetId;
    private Long targetThreadId;
    private ReportReason reason;
    private String detail;
    private ReportStatus status;
    private Instant createdAt;

    public ReportResponse() {
    }

    public ReportResponse(Long id,
                          Long reporterId,
                          ReportTargetType targetType,
                          Long targetId,
                          ReportReason reason,
                          String detail,
                          ReportStatus status,
                          Instant createdAt) {
        this(id, reporterId, targetType, targetId, reason, detail, status, createdAt, null);
    }

    public ReportResponse(Long id,
                          Long reporterId,
                          ReportTargetType targetType,
                          Long targetId,
                          ReportReason reason,
                          String detail,
                          ReportStatus status,
                          Instant createdAt,
                          Long targetThreadId) {
        this.id = id;
        this.reporterId = reporterId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.targetThreadId = targetThreadId;
        this.reason = reason;
        this.detail = detail;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getReporterId() {
        return reporterId;
    }

    public void setReporterId(Long reporterId) {
        this.reporterId = reporterId;
    }

    public ReportTargetType getTargetType() {
        return targetType;
    }

    public void setTargetType(ReportTargetType targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public Long getTargetThreadId() {
        return targetThreadId;
    }

    public void setTargetThreadId(Long targetThreadId) {
        this.targetThreadId = targetThreadId;
    }

    public ReportReason getReason() {
        return reason;
    }

    public void setReason(ReportReason reason) {
        this.reason = reason;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public void setStatus(ReportStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
