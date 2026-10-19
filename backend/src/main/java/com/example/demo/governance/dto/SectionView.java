package com.example.demo.governance.dto;

public record SectionView(
        Long id,
        String name,
        String slug,
        String description,
        String rules,
        boolean visible,
        boolean archived
) {
}
