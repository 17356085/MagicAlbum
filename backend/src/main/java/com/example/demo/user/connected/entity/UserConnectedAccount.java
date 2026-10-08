package com.example.demo.user.connected.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "user_connected_accounts",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_user_connected_accounts_user_provider", columnNames = {"user_id", "provider"}),
                @UniqueConstraint(name = "uk_user_connected_accounts_provider_external", columnNames = {"provider", "external_id"})
        }
)
public class UserConnectedAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(name = "external_id", length = 255)
    private String externalId;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "linked_at")
    private Instant linkedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }

    public void setLinkedAt(Instant linkedAt) {
        this.linkedAt = linkedAt;
    }
}
