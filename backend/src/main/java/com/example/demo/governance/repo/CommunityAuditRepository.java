package com.example.demo.governance.repo;

import com.example.demo.governance.entity.CommunityAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityAuditRepository extends JpaRepository<CommunityAudit, Long> {
    Page<CommunityAudit> findAllByOrderByOccurredAtDescIdDesc(Pageable pageable);
}
