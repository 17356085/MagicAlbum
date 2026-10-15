package com.example.demo.tags.dto;

import java.util.List;
import org.springframework.data.domain.Page;

public record TagPageResponse(List<TagStatsDto> items, Long sectionId, int page, int size, long total) {
    public static TagPageResponse from(Page<TagStatsDto> result, Long sectionId) {
        return new TagPageResponse(result.getContent(), sectionId, result.getNumber() + 1,
                result.getSize(), result.getTotalElements());
    }
}
