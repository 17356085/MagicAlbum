package com.example.demo.threads.service;

import com.example.demo.tags.service.TagService;
import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.dto.ThreadQueryView;
import com.example.demo.threads.dto.ThreadSearchDocument;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.event.entity.ContentEventOutbox;
import com.example.demo.threads.event.entity.ContentEventType;
import com.example.demo.threads.event.repo.ContentEventOutboxRepository;
import com.example.demo.threads.repo.ThreadLikeRepository;
import com.example.demo.threads.repo.ThreadRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Builds the durable search owner projection directly from the SQL source of truth. */
@Service
public class ThreadSearchProjectionService {
    private final ThreadRepository threadRepository;
    private final ContentEventOutboxRepository contentEventOutboxRepository;
    private final TagService tagService;
    private final ThreadDtoMapper dtoMapper;

    public ThreadSearchProjectionService(ThreadRepository threadRepository,
                                         ContentEventOutboxRepository contentEventOutboxRepository,
                                         ThreadLikeRepository threadLikeRepository,
                                         TagService tagService) {
        this.threadRepository = Objects.requireNonNull(threadRepository, "threadRepository");
        this.contentEventOutboxRepository = Objects.requireNonNull(
                contentEventOutboxRepository, "contentEventOutboxRepository");
        this.tagService = tagService;
        this.dtoMapper = new ThreadDtoMapper(threadRepository, threadLikeRepository, tagService);
    }

    @Transactional(readOnly = true)
    public Optional<ThreadSearchDocument> read(long id) {
        return threadRepository.findById(id).map(thread -> toDocument(
                thread, "NORMAL".equals(thread.getStatus()) ? tagsFor(thread) : List.of()));
    }

    @Transactional(readOnly = true)
    public List<ThreadSearchDocument> scanAfter(long afterId, int limit) {
        if (afterId < 0) {
            throw new IllegalArgumentException("afterId must be non-negative");
        }
        int boundedLimit = requireLimit(limit);
        List<Thread> threads = threadRepository.findByIdGreaterThanOrderByIdAsc(
                afterId, PageRequest.of(0, boundedLimit));
        Map<Long, List<String>> tagsByThread = tagsFor(threads);
        return threads.stream()
                .map(thread -> toDocument(thread,
                        "NORMAL".equals(thread.getStatus()) ? tagsByThread.get(thread.getId()) : List.of()))
                .toList();
    }

    /**
     * Revalidates an indexed hit against the locked SQL row and maps the current
     * query projection. This intentionally bypasses the thread cache.
     */
    @Transactional(readOnly = true)
    public Optional<ThreadDto> visibleResult(long id, long expectedVersion) {
        if (id <= 0 || expectedVersion < 0) {
            return Optional.empty();
        }
        Thread thread = threadRepository.findByIdForSearchProjection(id).orElse(null);
        if (thread == null || !"NORMAL".equals(thread.getStatus())
                || thread.getContentVersion() != expectedVersion) {
            return Optional.empty();
        }
        return threadRepository.findViewById(id).map(dtoMapper::toDto);
    }

    /** Reads the authoritative JPA query projection without Redis or MyBatis caches. */
    @Transactional(readOnly = true)
    public Page<ThreadDto> databaseSearch(String q, String tag, Long sectionId, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 10);
        int pageIndex = Math.max(page, 1) - 1;
        String normalizedQuery = normalizeNullable(q);
        String normalizedTag = normalizeNullable(tag);
        Page<ThreadQueryView> result = threadRepository.searchNewestView(
                normalizedQuery,
                normalizedTag,
                sectionId,
                PageRequest.of(pageIndex, limit));
        List<ThreadDto> items = result.getContent().stream().map(dtoMapper::toDto).toList();
        return new PageImpl<>(items, result.getPageable(), result.getTotalElements());
    }

    private ThreadSearchDocument toDocument(Thread thread, List<String> rawTags) {
        if (thread == null || thread.getId() == null) {
            throw new IllegalStateException("Thread projection requires an id");
        }
        long version = thread.getContentVersion();
        if (version < 0) {
            throw new IllegalStateException("Thread content version must be non-negative");
        }
        String eventType = eventTypeForStatus(thread.getStatus());
        String eventId;
        if (version == 0) {
            eventId = "baseline:" + thread.getId() + ":0";
        } else {
            ContentEventOutbox event = contentEventOutboxRepository
                    .findByAggregateIdAndAggregateVersion(thread.getId(), version)
                    .orElseThrow(() -> new IllegalStateException(
                            "Missing content event for thread " + thread.getId() + " version " + version));
            if (event.getEventId() == null || event.getEventId().isBlank() || event.getEventType() == null) {
                throw new IllegalStateException("Content event provenance is incomplete");
            }
            eventId = event.getEventId();
            eventType = event.getEventType().name();
        }

        boolean visible = "NORMAL".equals(thread.getStatus());
        String title = visible ? Objects.requireNonNullElse(thread.getTitle(), "") : "";
        String content = visible ? Objects.requireNonNullElse(thread.getContentMd(), "") : "";
        List<String> tags = visible ? normalizeTags(rawTags) : List.of();
        return new ThreadSearchDocument(
                thread.getId(),
                version,
                visible,
                title,
                content,
                tags,
                thread.getSectionId(),
                thread.getCreatedAt(),
                eventId,
                eventType);
    }

    private String eventTypeForStatus(String status) {
        if (status == null) {
            throw new IllegalStateException("Thread status is missing");
        }
        return switch (status) {
            case "NORMAL" -> ContentEventType.THREAD_UPSERTED.name();
            case "HIDDEN" -> ContentEventType.THREAD_HIDDEN.name();
            case "DELETED" -> ContentEventType.THREAD_DELETED.name();
            default -> throw new IllegalStateException("Unknown thread status: " + status);
        };
    }

    private List<String> tagsFor(Thread thread) {
        if (tagService == null || thread.getId() == null) {
            return List.of();
        }
        return tagService.getNamesByThreadId(thread.getId());
    }

    private Map<Long, List<String>> tagsFor(List<Thread> threads) {
        if (tagService == null || threads.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = threads.stream()
                .filter(thread -> "NORMAL".equals(thread.getStatus()))
                .map(Thread::getId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> result = tagService.getNamesByThreadIds(ids);
        return result == null ? Map.of() : result;
    }

    private List<String> normalizeTags(List<String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) {
            return List.of();
        }
        return rawTags.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .map(tag -> tag.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private int requireLimit(int limit) {
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("limit must be between 1 and 50");
        }
        return limit;
    }

}
