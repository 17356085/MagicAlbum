package com.example.demo.threads.service;

import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.dto.ThreadQueryView;
import com.example.demo.threads.dto.ThreadRankingView;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.threads.repo.ThreadLikeRepository;
import com.example.demo.tags.service.TagService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        if (t == null) return null;
        List<ThreadDto> items = toDtosFromEntities(List.of(t));
        return items.isEmpty() ? null : items.get(0);
    }

    List<ThreadDto> toDtosFromEntities(List<Thread> threads) {
        if (threads == null || threads.isEmpty()) return List.of();
        List<Long> ids = threads.stream().map(Thread::getId).toList();
        Map<Long, ThreadQueryView> viewMap = new HashMap<>();
        for (ThreadQueryView view : threadRepository.findViewsByIdIn(ids)) {
            viewMap.put(view.getId(), view);
        }
        return ids.stream()
                .map(viewMap::get)
                .filter(java.util.Objects::nonNull)
                .map(this::toDto)
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
        return dto;
    }

    ThreadDto toDto(ThreadRankingView view) {
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
        dto.setTags(getThreadTags(view.getId()));
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
            return tagService.getNamesByThreadId(threadId);
        } catch (DataAccessException e) {
            return List.of();
        }
    }

}
