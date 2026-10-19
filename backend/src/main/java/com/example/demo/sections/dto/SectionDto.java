package com.example.demo.sections.dto;

import java.time.Instant;

public record SectionDto(Long id, String name, String slug, String description,
                         String rules, Boolean visible, Instant createdAt, Boolean archived) {
    public SectionDto(Long id, String name, String slug, String description, String rules, Boolean visible, Instant createdAt) {
        this(id, name, slug, description, rules, visible, createdAt, false);
    }
}
