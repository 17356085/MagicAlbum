package com.example.demo.tags.service;

import com.example.demo.tags.dto.TagStatsDto;
import com.example.demo.tags.entity.Tag;
import com.example.demo.tags.repo.TagRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class TagService {
    private static final String DEFAULT_TYPE = "thread";
    private static final int MAX_TAGS_PER_THREAD = 5;
    private static final int MAX_TAG_LENGTH = 32;

    private final TagRepository tagRepository;
    private final JdbcTemplate jdbcTemplate;

    public TagService(TagRepository tagRepository, JdbcTemplate jdbcTemplate) {
        this.tagRepository = tagRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public List<TagStatsDto> popular(Long sectionId, int size) {
        int limit = Math.min(Math.max(size, 1), 20);
        return tagRepository.findPopular(sectionId, PageRequest.of(0, limit)).stream()
                .map(view -> new TagStatsDto(
                        view.getId(),
                        view.getName(),
                        view.getType(),
                        view.getThreadCount() == null ? 0L : view.getThreadCount()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<TagStatsDto> search(String q, Long sectionId, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 50);
        int pageIndex = Math.max(page - 1, 0);
        String query = (q == null || q.isBlank()) ? null : q.trim();
        return tagRepository.search(query, sectionId, PageRequest.of(pageIndex, limit))
                .map(view -> new TagStatsDto(
                        view.getId(),
                        view.getName(),
                        view.getType(),
                        view.getThreadCount() == null ? 0L : view.getThreadCount()
                ));
    }

    @Transactional
    public void replaceThreadTags(Long threadId, List<String> rawTags) {
        if (threadId == null) {
            return;
        }
        List<String> names = normalizeTags(rawTags);
        jdbcTemplate.update("DELETE FROM thread_tags WHERE thread_id = ?", threadId);
        for (String name : names) {
            Tag tag = findOrCreate(name);
            try {
                jdbcTemplate.update(
                        "INSERT INTO thread_tags (thread_id, tag_id) VALUES (?, ?)",
                        threadId,
                        tag.getId()
                );
            } catch (DuplicateKeyException ignored) {
            }
        }
    }

    @Transactional(readOnly = true)
    public List<String> getNamesByThreadId(Long threadId) {
        if (threadId == null) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT tg.name
                FROM tags tg
                JOIN thread_tags tt ON tt.tag_id = tg.id
                WHERE tt.thread_id = ?
                ORDER BY tg.name ASC
                """,
                (rs, rowNum) -> rs.getString("name"),
                threadId
        );
    }

    @Transactional(readOnly = true)
    public Map<Long, List<String>> getNamesByThreadIds(Collection<Long> threadIds) {
        if (threadIds == null || threadIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = threadIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>(ids);
        Map<Long, List<String>> result = new HashMap<>();
        jdbcTemplate.query(
                """
                SELECT tt.thread_id, tg.name
                FROM thread_tags tt
                JOIN tags tg ON tg.id = tt.tag_id
                WHERE tt.thread_id IN (
                """ + placeholders + """
                )
                ORDER BY tg.name ASC
                """,
                rs -> {
                    Long threadId = rs.getLong("thread_id");
                    String name = rs.getString("name");
                    result.computeIfAbsent(threadId, ignored -> new ArrayList<>()).add(name);
                },
                args.toArray()
        );
        return result;
    }

    public List<String> normalizeTags(List<String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) {
            return List.of();
        }
        return rawTags.stream()
                .map(this::normalizeTag)
                .filter(tag -> tag != null && !tag.isBlank())
                .distinct()
                .limit(MAX_TAGS_PER_THREAD)
                .toList();
    }

    private Tag findOrCreate(String name) {
        return tagRepository.findByNameAndType(name, DEFAULT_TYPE)
                .orElseGet(() -> {
                    Tag tag = new Tag();
                    tag.setName(name);
                    tag.setType(DEFAULT_TYPE);
                    return tagRepository.save(tag);
                });
    }

    private String normalizeTag(String raw) {
        if (raw == null) {
            return "";
        }
        String tag = raw.trim();
        while (tag.startsWith("#")) {
            tag = tag.substring(1).trim();
        }
        tag = tag.replaceAll("\\s+", " ");
        if (tag.length() > MAX_TAG_LENGTH) {
            tag = tag.substring(0, MAX_TAG_LENGTH);
        }
        return tag;
    }
}
