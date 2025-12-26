package com.example.demo.sections.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "sections")
public class Section {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 128, unique = true)
    private String slug;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String rules;

    @Column(nullable = false)
    private Boolean visible = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (visible == null) visible = true;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getRules() { return rules; }
    public void setRules(String rules) { this.rules = rules; }
    public Boolean getVisible() { return visible; }
    public void setVisible(Boolean visible) { this.visible = visible; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}