package com.example.demo.ai.summary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/** Also binds successful cache hits, which do not create an external execution attempt. */
@Entity
@Table(name = "ai_summary_request", indexes = @Index(name = "idx_summary_request_task", columnList = "task_id"))
@lombok.Getter
@lombok.Setter
public class SummaryRequest {
    @EmbeddedId private SummaryRequestId id;
    @Column(name = "task_id", nullable = false, length = 36, updatable = false) private String taskId;
    @Column(name = "thread_id", nullable = false, updatable = false) private Long threadId;
    @Column(name = "content_version", nullable = false, updatable = false) private long contentVersion;
    @Column(name = "force_requested", nullable = false, updatable = false) private boolean forceRequested;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
}
