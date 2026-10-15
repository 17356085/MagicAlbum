package com.example.demo.tags.controller;

import com.example.demo.tags.dto.TagStatsDto;
import com.example.demo.tags.dto.TagPageResponse;
import com.example.demo.tags.dto.PopularTagsResponse;
import com.example.demo.tags.service.TagService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tags")
public class TagsController {
    private final TagService tagService;

    public TagsController(TagService tagService) {
        this.tagService = tagService;
    }

    @GetMapping
    public ResponseEntity<TagPageResponse> list(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "sectionId", required = false) Long sectionId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        Page<TagStatsDto> result = tagService.search(q, sectionId, page, size);
        return ResponseEntity.ok(TagPageResponse.from(result, sectionId));
    }

    @GetMapping("/popular")
    public ResponseEntity<PopularTagsResponse> popular(
            @RequestParam(value = "sectionId", required = false) Long sectionId,
            @RequestParam(value = "size", defaultValue = "12") int size
    ) {
        List<TagStatsDto> items = tagService.popular(sectionId, size);
        int effectiveSize = Math.min(Math.max(size, 1), 20);
        return ResponseEntity.ok(new PopularTagsResponse(items, sectionId, effectiveSize, items.size()));
    }
}
