package com.example.demo.ai.summary.dto;

import java.time.Instant;

/** Execution metadata is not proof of successful generation; COMPLETED also requires content readback. */
public record SummaryView(Long threadId, String taskId, long contentVersion, Long taskContentVersion,
                          String status, String summary, boolean canManage, int attempts, String errorCode,
                          String provider, String model, Long inputTokens, Long outputTokens,
                          boolean usageEstimated, long chargedTokens, String requestId, Instant updatedAt) { }
