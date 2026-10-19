package com.example.demo.search.service;

import com.example.demo.search.port.ContentSearchIndex;
import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.maintenance.service.ContentWriteGateService;
import com.example.demo.threads.service.ThreadSearchProjectionService;
import com.example.demo.threads.service.ThreadService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

@Service
public class ThreadSearchService {
    private final ThreadService threads;
    private final ThreadSearchProjectionService projections;
    private final ContentWriteGateService control;
    private final ObjectProvider<ContentSearchIndex> indexProvider;
    private final MeterRegistry metrics;

    public ThreadSearchService(ThreadService threads, ThreadSearchProjectionService projections,
                               ContentWriteGateService control, ObjectProvider<ContentSearchIndex> indexProvider,
                               MeterRegistry metrics) {
        this.threads = threads;
        this.projections = projections;
        this.control = control;
        this.indexProvider = indexProvider;
        this.metrics = metrics;
    }

    public Page<ThreadDto> list(String q, String tag, Long sectionId, int page, int size) {
        ContentSearchIndex index = indexProvider.getIfAvailable();
        if (index == null || q == null || q.isBlank()) {
            return threads.list(q, tag, sectionId, page, size);
        }
        int limit = Math.min(Math.max(size, 1), 10);
        int pageNumber = Math.max(page, 1);
        String query = q.trim();
        String normalizedTag = tag == null || tag.isBlank() ? null : tag.trim().toLowerCase(Locale.ROOT);
        String fallbackReason = "unavailable";
        try {
            var state = control.snapshot();
            if (!state.ready() || state.activeIndex() == null) {
                fallbackReason = "not_ready";
            } else if (!index.activeIndex().filter(state.activeIndex()::equals).isPresent()) {
                // Disable a divergent pointer without invalidating a concurrent successful rebuild.
                control.invalidateIfActive(state.activeIndex());
                fallbackReason = "not_ready";
            } else {
                var result = index.search(ContentSearchIndex.ALIAS, query, normalizedTag, sectionId, pageNumber, limit);
                List<ThreadDto> items = new ArrayList<>();
                HashSet<Long> seen = new HashSet<>();
                boolean fresh = result.hits().size() <= limit && result.total() >= result.hits().size();
                for (var hit : result.hits()) {
                    if (!fresh || hit.id() <= 0 || hit.version() < 0 || !seen.add(hit.id())) {
                        fresh = false;
                        break;
                    }
                    var current = projections.visibleResult(hit.id(), hit.version());
                    if (current.isEmpty()) {
                        fresh = false;
                        break;
                    }
                    items.add(current.orElseThrow());
                }
                if (fresh) {
                    metrics.counter("content.search.requests", "source", "elasticsearch").increment();
                    return new PageImpl<>(items, PageRequest.of(pageNumber - 1, limit), result.total());
                }
                fallbackReason = "stale_projection";
            }
        } catch (RuntimeException failure) {
            // Dependency failures are not empty search results; the owner remains authoritative.
            fallbackReason = "unavailable";
        }
        metrics.counter("content.search.fallback", "reason", fallbackReason).increment();
        return projections.databaseSearch(q, tag, sectionId, page, size);
    }
}
