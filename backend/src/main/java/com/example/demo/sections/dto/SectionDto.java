package com.example.demo.sections.dto;

import java.time.Instant;

public record SectionDto(Long id, String name, String slug, String description,
                         String rules, Boolean visible, Instant createdAt) {
}
