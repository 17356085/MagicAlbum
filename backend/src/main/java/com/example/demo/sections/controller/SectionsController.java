package com.example.demo.sections.controller;

import com.example.demo.common.PageResponse;
import com.example.demo.sections.entity.Section;
import com.example.demo.sections.dto.SectionDto;
import com.example.demo.sections.service.SectionService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/sections")
public class SectionsController {
    private final SectionService sectionService;

    public SectionsController(SectionService sectionService) {
        this.sectionService = sectionService;
    }

    @GetMapping
    public ResponseEntity<PageResponse<SectionDto>> list(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        Page<Section> p = sectionService.list(q, page, size);
        return ResponseEntity.ok(PageResponse.from(p.map(section -> new SectionDto(section.getId(),
                section.getName(), section.getSlug(), section.getDescription(), section.getRules(),
                section.getVisible(), section.getCreatedAt()))));
    }
}
