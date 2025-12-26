package com.example.demo.sections.controller;

import com.example.demo.sections.entity.Section;
import com.example.demo.sections.service.SectionService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sections")
public class SectionsController {
    private final SectionService sectionService;

    public SectionsController(SectionService sectionService) {
        this.sectionService = sectionService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        Page<Section> p = sectionService.list(q, page, size);
        Map<String, Object> body = new HashMap<>();
        body.put("items", p.getContent());
        body.put("page", page);
        body.put("size", size);
        body.put("total", p.getTotalElements());
        return ResponseEntity.ok(body);
    }
}