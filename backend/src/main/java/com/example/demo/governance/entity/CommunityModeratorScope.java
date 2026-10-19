package com.example.demo.governance.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "community_moderator_scope")
public class CommunityModeratorScope {
    @EmbeddedId
    private ModeratorScopeId id;

    public CommunityModeratorScope() {
    }

    public CommunityModeratorScope(Long userId, Long sectionId) {
        this.id = new ModeratorScopeId(userId, sectionId);
    }

    public ModeratorScopeId getId() {
        return id;
    }

    public void setId(ModeratorScopeId id) {
        this.id = id;
    }
}
