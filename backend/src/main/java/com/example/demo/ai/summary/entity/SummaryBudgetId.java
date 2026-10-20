package com.example.demo.ai.summary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.LocalDate;

@Embeddable
@lombok.Data
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
public class SummaryBudgetId implements Serializable {
    private static final long serialVersionUID = 1L;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "budget_day", nullable = false) private LocalDate day;
}
