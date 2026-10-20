package com.example.demo.ai.summary.repo;

import com.example.demo.ai.summary.entity.SummaryRequest;
import com.example.demo.ai.summary.entity.SummaryRequestId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SummaryRequestRepository extends JpaRepository<SummaryRequest, SummaryRequestId> { }
