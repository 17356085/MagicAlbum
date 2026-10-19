package com.example.demo.governance.dto;

public record SectionRequest(
        String name,
        String slug,
        String description,
        String rules,
        Boolean visible,
        Boolean archived,
        String reason
) {
}
