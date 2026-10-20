package com.example.demo.ai.summary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;

/** One immutable budget reservation per external attempt, with subsequent outcome settlement. */
@Entity
@Table(name = "ai_summary_attempt", uniqueConstraints = @UniqueConstraint(name = "uk_summary_attempt",
        columnNames = {"task_id", "attempt_number"}))
@lombok.Getter
@lombok.Setter
public class SummaryAttempt {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "task_id", nullable = false, length = 36, updatable = false) private String taskId;
    @Column(name = "attempt_number", nullable = false, updatable = false) private int attemptNumber;
    @Column(name = "lease_owner", nullable = false, length = 36, updatable = false) private String leaseOwner;
    @Column(name = "budget_day", nullable = false, updatable = false) private LocalDate budgetDay;
    @Column(name = "reserved_tokens", nullable = false, updatable = false) private long reservedTokens;
    @Column(name = "charged_tokens", nullable = false) private long chargedTokens;
    @Column(name = "usage_estimated", nullable = false) private boolean usageEstimated = true;
    @Column(nullable = false, length = 32) private String status;
    @Column(nullable = false, length = 128) private String provider;
    @Column(nullable = false, length = 256) private String model;
    @Column(name = "response_model", length = 256) private String responseModel;
    @Column(name = "provider_request_id", length = 128) private String providerRequestId;
    @Column(name = "provider_response_id", length = 128) private String providerResponseId;
    @Column(name = "input_tokens") private Long inputTokens;
    @Column(name = "output_tokens") private Long outputTokens;
    @Column(name = "duration_millis", nullable = false) private long durationMillis;
    @Column(name = "error_code", length = 32) private String errorCode;
    @Column(name = "started_at", nullable = false, updatable = false) private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
}
