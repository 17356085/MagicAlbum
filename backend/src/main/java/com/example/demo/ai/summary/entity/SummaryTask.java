package com.example.demo.ai.summary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "ai_summary_task", uniqueConstraints = @UniqueConstraint(name = "uk_summary_request",
        columnNames = {"triggered_by", "request_key"}), indexes = {
        @Index(name = "idx_summary_recovery", columnList = "status,available_at,task_id"),
        @Index(name = "idx_summary_thread", columnList = "thread_id,content_version"),
        @Index(name = "idx_summary_actor", columnList = "triggered_by,status")})
@lombok.Getter
@lombok.Setter
public class SummaryTask {
    @Id @Column(name = "task_id", length = 36) private String taskId;
    @Column(name = "thread_id", nullable = false, updatable = false) private Long threadId;
    @Column(name = "content_version", nullable = false, updatable = false) private long contentVersion;
    @Column(name = "triggered_by", nullable = false, updatable = false) private Long triggeredBy;
    @Column(name = "request_key", nullable = false, length = 36, updatable = false) private String requestKey;
    @Column(name = "force_requested", nullable = false, updatable = false) private boolean forceRequested;
    @Column(name = "input_sha256", nullable = false, length = 64, updatable = false) private String inputSha256;
    @Column(name = "input_chars", nullable = false, updatable = false) private int inputChars;
    @Column(nullable = false, length = 32) private String status;
    @Column(nullable = false) private int attempts;
    @Column(name = "lease_owner", length = 36) private String leaseOwner;
    @Column(name = "lease_until") private Instant leaseUntil;
    @Column(name = "available_at", nullable = false) private Instant availableAt;
    @Column(name = "error_code", length = 32) private String errorCode;
    @Column(columnDefinition = "TEXT") private String summary;
    @Column(name = "request_id", nullable = false, length = 128, updatable = false) private String requestId;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
