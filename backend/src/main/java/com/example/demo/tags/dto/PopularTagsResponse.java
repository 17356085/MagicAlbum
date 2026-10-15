package com.example.demo.tags.dto;

import java.util.List;

/** Ranked subset, not a paginated search result. */
public record PopularTagsResponse(List<TagStatsDto> items, Long sectionId, int size, long total) {
}
