package com.example.demo.threads.controller;

import com.example.demo.threads.dto.SectionThreadPreviewDto;
import com.example.demo.threads.service.ThreadSectionPreviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Dedicated batch read endpoint for the section list preview. */
@RestController
@RequestMapping("/api/v1/threads")
public class ThreadSectionPreviewController {
    private final ThreadSectionPreviewService service;

    public ThreadSectionPreviewController(ThreadSectionPreviewService service) {
        this.service = service;
    }

    @GetMapping("/section-previews")
    public ResponseEntity<List<SectionThreadPreviewDto>> read(
            @RequestParam("sectionIds") List<Long> sectionIds) {
        return ResponseEntity.ok(service.read(sectionIds));
    }
}
