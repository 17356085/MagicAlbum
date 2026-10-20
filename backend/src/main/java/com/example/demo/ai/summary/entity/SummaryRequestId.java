package com.example.demo.ai.summary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;

@Embeddable
@lombok.Data
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
public class SummaryRequestId implements Serializable {
    private static final long serialVersionUID = 1L;
    @Column(name = "triggered_by", nullable = false) private Long actor;
    @Column(name = "request_key", nullable = false, length = 36) private String key;
}
