package com.example.demo.threads.service;

import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.dto.ThreadQueryView;
import com.example.demo.threads.dto.ThreadRankingView;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.threads.repo.ThreadLikeRepository;
import com.example.demo.tags.service.TagService;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DataAccessException;

/** Assembles public read models from projections and optional enrichment. */
final class ThreadDtoMapper {
    private final ThreadRepository threadRepository;
    private final ThreadLikeRepository threadLikeRepository;
    private final TagService tagService;

    ThreadDtoMapper(ThreadRepository threadRepository, ThreadLikeRepository threadLikeRepository, TagService tagService) {
        this.threadRepository = threadRepository;
        this.threadLikeRepository = threadLikeRepository;
        this.tagService = tagService;
    }

    ThreadDto toDto(Thread t) {
        if (t == null || t.getId() == null) return null;
        List<ThreadQueryView> views = threadRepository.findViewsByIdIn(List.of(t.getId()));
        if (views == null) return null;
        return views.stream()
                .filter(Objects::nonNull)
                .filter(view -> t.getId().equals(view.getId()))
                .findFirst()
                .map(this::toDto)
                .orElse(null);
    }

    List<ThreadDto> toDtosFromEntities(List<Thread> threads) {
        if (threads == null || threads.isEmpty()) return List.of();
        List<Long> ids = threads.stream()
                .filter(Objects::nonNull)
                .map(Thread::getId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) return List.of();
        Map<Long, ThreadQueryView> viewMap = new HashMap<>();
        List<ThreadQueryView> views = threadRepository.findViewsByIdIn(ids);
        if (views == null || views.isEmpty()) return List.of();
        for (ThreadQueryView view : views) {
            if (view != null && view.getId() != null && "NORMAL".equals(view.getStatus())) {
                viewMap.put(view.getId(), view);
            }
        }
        List<ThreadQueryView> orderedViews = ids.stream()
                .map(viewMap::get)
                .filter(Objects::nonNull)
                .toList();
        return toDtos(orderedViews);
    }

    List<ThreadDto> toDtos(List<ThreadQueryView> views) {
        if (views == null || views.isEmpty()) return List.of();
        List<Long> ids = views.stream()
                .map(ThreadQueryView::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Long> likeCounts = batchLikeCounts(ids);
        Map<Long, List<String>> tags = batchTags(ids);
        return views.stream()
                .map(view -> toDto(view,
                        likeCounts.getOrDefault(view.getId(), 0L),
                        tags.getOrDefault(view.getId(), List.of())))
                .toList();
    }

    ThreadDto toDto(ThreadQueryView view) {
        ThreadDto dto = toBaseDto(view);
        dto.setLikeCount(countLikes(view.getId()));
        dto.setTags(getThreadTags(view.getId()));
        return dto;
    }

    ThreadDto toFeedDto(com.example.demo.threads.dto.ThreadFeedView view) {
        ThreadDto dto = toBaseDto(view);
        dto.setReplyCount(view.getReplyCount() == null ? 0 : view.getReplyCount());
        dto.setLikeCount(view.getLikeCount() == null ? 0 : view.getLikeCount());
        return dto;
    }

    ThreadDto toBaseDto(ThreadQueryView view) {
        ThreadDto dto = new ThreadDto();
        dto.setId(view.getId());
        dto.setSectionId(view.getSectionId());
        dto.setSectionName(view.getSectionName());
        dto.setAuthorId(view.getAuthorId());
        dto.setAuthorUsername(view.getAuthorUsername());
        dto.setAuthorNickname(view.getAuthorNickname());
        dto.setAuthorAvatar(view.getAuthorAvatar());
        dto.setTitle(view.getTitle());
        dto.setContent(view.getContent());
        dto.setStatus(view.getStatus());
        dto.setCreatedAt(view.getCreatedAt());
        dto.setUpdatedAt(view.getUpdatedAt());
        dto.setReplyCount(view.getReplyCount() == null ? 0 : view.getReplyCount());
        return dto;
    }

    ThreadDto toDto(ThreadRankingView view) {
        return toRankingDto(view, getThreadTags(view.getId()));
    }

    List<ThreadDto> toRankingDtos(List<ThreadRankingView> views) {
        if (views == null || views.isEmpty()) return List.of();
        List<Long> ids = views.stream()
                .map(ThreadRankingView::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, List<String>> tags = batchTags(ids);
        return views.stream()
                .map(view -> toRankingDto(view, tags.getOrDefault(view.getId(), List.of())))
                .toList();
    }

    private ThreadDto toRankingDto(ThreadRankingView view, List<String> tags) {
        ThreadDto dto = new ThreadDto();
        dto.setId(view.getId());
        dto.setSectionId(view.getSectionId());
        dto.setSectionName(view.getSectionName());
        dto.setAuthorId(view.getAuthorId());
        dto.setAuthorUsername(view.getAuthorUsername());
        dto.setAuthorNickname(view.getAuthorNickname());
        dto.setAuthorAvatar(view.getAuthorAvatar());
        dto.setTitle(view.getTitle());
        dto.setContent(view.getContent());
        dto.setStatus(view.getStatus());
        dto.setCreatedAt(view.getCreatedAt());
        dto.setUpdatedAt(view.getUpdatedAt());
        dto.setReplyCount(view.getReplyCount() == null ? 0 : view.getReplyCount());
        dto.setLikeCount(view.getLikeCount() == null ? 0 : view.getLikeCount());
        dto.setHotScore(view.getHotScore() == null ? 0 : view.getHotScore());
        dto.setTags(tags == null ? List.of() : tags);
        return dto;
    }

    private ThreadDto toDto(ThreadQueryView view, long likeCount, List<String> tags) {
        ThreadDto dto = toBaseDto(view);
        dto.setLikeCount(likeCount);
        dto.setTags(tags == null ? List.of() : tags);
        return dto;
    }

    long countLikes(Long threadId) {
        if (threadLikeRepository == null || threadId == null) {
            return 0;
        }
        try {
            return threadLikeRepository.countByThreadId(threadId);
        } catch (DataAccessException e) {
            return 0;
        }
    }

    private List<String> getThreadTags(Long threadId) {
        if (tagService == null) {
            return List.of();
        }
        try {
            List<String> tags = tagService.getNamesByThreadId(threadId);
            return tags == null ? List.of() : tags;
        } catch (DataAccessException e) {
            return List.of();
        }
    }

    private Map<Long, Long> batchLikeCounts(Collection<Long> threadIds) {
        Map<Long, Long> counts = new HashMap<>();
        if (threadLikeRepository == null || threadIds == null || threadIds.isEmpty()) {
            return counts;
        }
        List<Long> ids = threadIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return counts;
        try {
            List<com.example.demo.threads.dto.ThreadLikeCountView> rows =
                    threadLikeRepository.findLikeCountsByThreadIds(ids);
            if (rows == null) return counts;
            for (com.example.demo.threads.dto.ThreadLikeCountView row : rows) {
                if (row != null && row.getThreadId() != null && row.getLikeCount() != null) {
                    counts.put(row.getThreadId(), row.getLikeCount());
                }
            }
        } catch (DataAccessException e) {
            return new HashMap<>();
        }
        return counts;
    }

    private Map<Long, List<String>> batchTags(Collection<Long> threadIds) {
        if (tagService == null || threadIds == null || threadIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = threadIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        try {
            Map<Long, List<String>> result = tagService.getNamesByThreadIds(ids);
            return result == null ? Map.of() : result;
        } catch (DataAccessException e) {
            return Map.of();
        }
    }

}
