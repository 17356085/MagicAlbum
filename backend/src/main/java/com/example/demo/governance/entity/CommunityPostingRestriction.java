package com.example.demo.governance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "community_posting_restriction")
public class CommunityPostingRestriction {
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "restricted_until")
    private Instant restrictedUntil;

    public CommunityPostingRestriction() {
    }

    public CommunityPostingRestriction(Long userId, Instant restrictedUntil) {
        this.userId = userId;
        this.restrictedUntil = restrictedUntil;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Instant getRestrictedUntil() {
        return restrictedUntil;
    }

    public void setRestrictedUntil(Instant restrictedUntil) {
        this.restrictedUntil = restrictedUntil;
    }
}
