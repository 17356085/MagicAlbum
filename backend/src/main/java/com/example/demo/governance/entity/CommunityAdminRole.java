package com.example.demo.governance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "community_admin_role")
public class CommunityAdminRole {
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    public CommunityAdminRole() {
    }

    public CommunityAdminRole(Long userId) {
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }
}
