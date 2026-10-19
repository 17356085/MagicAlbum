package com.example.demo.governance.repo;

import com.example.demo.governance.entity.CommunityPostingRestriction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityPostingRestrictionRepository extends JpaRepository<CommunityPostingRestriction, Long> {
}
