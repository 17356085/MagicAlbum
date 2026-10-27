package com.example.demo.threads.dto;

import java.util.List;

/** The bounded latest-thread preview for one requested section. */
public record SectionThreadPreviewDto(
        String sectionId,
        long total,
        List<ThreadPreviewDto> items
) {
    public SectionThreadPreviewDto {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
