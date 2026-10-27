package com.example.demo.threads.service;

import com.example.demo.threads.dto.SectionThreadPreviewDto;
import com.example.demo.threads.dto.ThreadPreviewDto;
import com.example.demo.threads.repo.ThreadSectionPreviewRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Reads a bounded, ordered preview for a set of sections in one database query. */
@Service
public class ThreadSectionPreviewService {
    static final int MAX_SECTION_IDS = 50;
    static final int PREVIEW_SIZE = 4;

    private final ThreadSectionPreviewRepository repository;

    public ThreadSectionPreviewService(ThreadSectionPreviewRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<SectionThreadPreviewDto> read(List<Long> sectionIds) {
        validate(sectionIds);
        LinkedHashSet<Long> uniqueIds = new LinkedHashSet<>(sectionIds);
        if (uniqueIds.isEmpty()) {
            return List.of();
        }

        Map<String, List<ThreadSectionPreviewRepository.PreviewRow>> rowsBySection = new LinkedHashMap<>();
        for (ThreadSectionPreviewRepository.PreviewRow row : repository.findLatestBySectionIds(uniqueIds)) {
            rowsBySection.computeIfAbsent(row.sectionId(), ignored -> new ArrayList<>()).add(row);
        }

        return uniqueIds.stream()
                .map(sectionId -> toDto(sectionId, rowsBySection.get(String.valueOf(sectionId))))
                .toList();
    }

    private SectionThreadPreviewDto toDto(
            Long sectionId,
            List<ThreadSectionPreviewRepository.PreviewRow> rows) {
        List<ThreadSectionPreviewRepository.PreviewRow> sectionRows = rows == null ? List.of() : rows;
        long total = sectionRows.isEmpty() ? 0L : sectionRows.get(0).total();
        List<ThreadPreviewDto> items = sectionRows.stream()
                .limit(PREVIEW_SIZE)
                .map(row -> new ThreadPreviewDto(
                        row.threadId(),
                        row.title(),
                        row.authorId(),
                        row.authorUsername(),
                        row.authorNickname(),
                        row.createdAt(),
                        ThreadSectionPreviewCoverExtractor.extract(row.content())
                ))
                .toList();
        return new SectionThreadPreviewDto(String.valueOf(sectionId), total, items);
    }

    private void validate(List<Long> sectionIds) {
        if (sectionIds == null) {
            throw invalid("sectionIds 不能为空");
        }
        if (sectionIds.size() > MAX_SECTION_IDS) {
            throw invalid("sectionIds 最多支持 " + MAX_SECTION_IDS + " 个分区");
        }
        for (int index = 0; index < sectionIds.size(); index++) {
            Long sectionId = sectionIds.get(index);
            if (sectionId == null || sectionId <= 0) {
                throw invalid("sectionIds[" + index + "] 必须是正整数");
            }
        }
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
