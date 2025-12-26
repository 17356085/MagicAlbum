package com.example.demo.sections.service;

import com.example.demo.sections.entity.Section;
import com.example.demo.sections.repo.SectionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class SectionService {
    private final SectionRepository sectionRepository;

    public SectionService(SectionRepository sectionRepository) {
        this.sectionRepository = sectionRepository;
    }

    public Page<Section> list(String q, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 100));
        return sectionRepository.search(q == null || q.isBlank() ? null : q, pr);
    }
}