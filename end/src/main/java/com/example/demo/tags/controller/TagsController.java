package com.example.demo.tags.controller;

import com.example.demo.tags.dto.TagStatsDto;
import com.example.demo.tags.service.TagService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tags")
public class TagsController {
    private final TagService tagService;

    public TagsController(TagService tagService) {
        this.tagService = tagService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "sectionId", required = false) Long sectionId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        Page<TagStatsDto> result = tagService.search(q, sectionId, page, size);
        Map<String, Object> body = new HashMap<>();
        body.put("items", result.getContent());
        body.put("sectionId", sectionId);
        body.put("page", page);
        body.put("size", size);
        body.put("total", result.getTotalElements());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/popular")
    public ResponseEntity<Map<String, Object>> popular(
            @RequestParam(value = "sectionId", required = false) Long sectionId,
            @RequestParam(value = "size", defaultValue = "12") int size
    ) {
        List<TagStatsDto> items = tagService.popular(sectionId, size);
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        body.put("sectionId", sectionId);
        body.put("size", size);
        body.put("total", items.size());
        return ResponseEntity.ok(body);
    }
}
