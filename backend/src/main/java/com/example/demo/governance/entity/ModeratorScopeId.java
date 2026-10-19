package com.example.demo.governance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class ModeratorScopeId implements Serializable {
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "section_id", nullable = false)
    private Long sectionId;

    public ModeratorScopeId() {
    }

    public ModeratorScopeId(Long userId, Long sectionId) {
        this.userId = userId;
        this.sectionId = sectionId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getSectionId() {
        return sectionId;
    }

    public void setSectionId(Long sectionId) {
        this.sectionId = sectionId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ModeratorScopeId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(sectionId, that.sectionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, sectionId);
    }
}
