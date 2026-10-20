package com.example.demo.ai.summary.repo;

import com.example.demo.ai.summary.entity.SummaryBudget;
import com.example.demo.ai.summary.entity.SummaryBudgetId;
import org.springframework.data.jpa.repository.JpaRepository;

/** All access is serialized by the associated user's row lock. */
public interface SummaryBudgetRepository extends JpaRepository<SummaryBudget, SummaryBudgetId> { }
