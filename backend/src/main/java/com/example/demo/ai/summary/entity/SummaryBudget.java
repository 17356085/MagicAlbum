package com.example.demo.ai.summary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "ai_summary_budget")
@lombok.Getter
@lombok.Setter
public class SummaryBudget {
    @EmbeddedId private SummaryBudgetId id;
    @Column(name = "charged_tokens", nullable = false) private long chargedTokens;
}
