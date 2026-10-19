package com.example.demo.governance.repo;

import com.example.demo.governance.entity.CommunityAdminRole;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityAdminRoleRepository extends JpaRepository<CommunityAdminRole, Long> {
    boolean existsByUserId(Long userId);
}
