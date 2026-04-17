package com.example.demo.threads.service;

import com.example.demo.sections.repo.SectionRepository;
import com.example.demo.threads.dto.CreateThreadRequest;
import com.example.demo.threads.dto.ThreadQueryView;
import com.example.demo.threads.dto.UpdateThreadRequest;
import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.common.config.RabbitMQConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ThreadService {
    private final ThreadRepository threadRepository;
    private final SectionRepository sectionRepository;
    private final MarkdownRenderService markdownRenderService;
    private final com.example.demo.threads.service.mp.ThreadReadServiceMp threadReadServiceMp;
    private final boolean mpThreadsEnabled;
    private final RabbitTemplate rabbitTemplate;
    private final boolean rabbitEnabled;

    public ThreadService(ThreadRepository threadRepository, SectionRepository sectionRepository,
                         MarkdownRenderService markdownRenderService,
                         ObjectProvider<com.example.demo.threads.service.mp.ThreadReadServiceMp> threadReadServiceMpProvider,
                         @Value("${feature.mp.read.threads:false}") boolean mpThreadsEnabled,
                         ObjectProvider<RabbitTemplate> rabbitTemplateProvider,
                         @Value("${app.rabbit.enabled:true}") boolean rabbitEnabled) {
        this.threadRepository = threadRepository;
        this.sectionRepository = sectionRepository;
        this.markdownRenderService = markdownRenderService;
        this.threadReadServiceMp = threadReadServiceMpProvider.getIfAvailable();
        this.mpThreadsEnabled = mpThreadsEnabled;
        this.rabbitTemplate = rabbitTemplateProvider.getIfAvailable();
        this.rabbitEnabled = rabbitEnabled;
    }

    public ThreadDto create(Long authorId, CreateThreadRequest req) {
        // 校验分区存在
        Long sectionId = req.getSectionId();
        if (sectionId == null || !sectionRepository.existsById(sectionId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分区不存在");
        }
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题不能为空");
        }
        if (req.getContent() == null || req.getContent().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容不能为空");
        }

        Thread t = new Thread();
        t.setSectionId(sectionId);
        t.setAuthorId(authorId);
        t.setTitle(req.getTitle().trim());
        t.setContentMd(req.getContent());
        Thread saved = threadRepository.save(t);

        // 触发异步摘要生成
        if (rabbitEnabled && rabbitTemplate != null) {
            try {
                rabbitTemplate.convertAndSend(RabbitMQConfig.QUEUE_THREAD_SUMMARY, saved.getId());
            } catch (Exception e) {
                System.err.println("Failed to send summary generation task: " + e.getMessage());
            }
        }

        return toDto(saved);
    }

    public Page<ThreadDto> list(String q, Long sectionId, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 10);
        int pageIndex = Math.max(page - 1, 0);
        if (mpThreadsEnabled && threadReadServiceMp != null) {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<Thread> mpPage = threadReadServiceMp.searchNewest(q, sectionId, page, limit);
            java.util.List<ThreadDto> items = toDtosFromEntities(mpPage.getRecords());
            return new org.springframework.data.domain.PageImpl<>(items, PageRequest.of(pageIndex, limit), mpPage.getTotal());
        }
        PageRequest pr = PageRequest.of(pageIndex, limit);
        Page<ThreadQueryView> p = threadRepository.searchNewestView(
                (q == null || q.isBlank()) ? null : q,
                sectionId,
                pr
        );
        return new org.springframework.data.domain.PageImpl<>(p.getContent().stream().map(this::toDto).toList(), p.getPageable(), p.getTotalElements());
    }

    public Page<ThreadDto> listByAuthor(Long authorId, String sort, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 10));
        Page<ThreadQueryView> p;
        String s = (sort == null || sort.isBlank()) ? "updatedAt" : sort;
        if ("createdAt".equalsIgnoreCase(s)) {
            p = threadRepository.findByAuthorCreatedDescView(authorId, pr);
        } else {
            p = threadRepository.findByAuthorNewestView(authorId, pr);
        }
        return new org.springframework.data.domain.PageImpl<>(p.getContent().stream().map(this::toDto).toList(), p.getPageable(), p.getTotalElements());
    }

    public Page<ThreadDto> listByAuthor(Long authorId, String q, Long sectionId, String sort, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 10));
        String s = (sort == null || sort.isBlank()) ? "updatedAt" : sort;
        String query = (q == null || q.isBlank()) ? null : q.trim();
        Long sid = sectionId;
        Page<ThreadQueryView> p;
        if ("createdAt".equalsIgnoreCase(s)) {
            p = threadRepository.searchByAuthorCreatedDescView(authorId, query, sid, pr);
        } else {
            p = threadRepository.searchByAuthorUpdatedDescView(authorId, query, sid, pr);
        }
        return new org.springframework.data.domain.PageImpl<>(p.getContent().stream().map(this::toDto).toList(), p.getPageable(), p.getTotalElements());
    }

    private ThreadDto toDto(Thread t) {
        if (t == null) return null;
        List<ThreadDto> items = toDtosFromEntities(List.of(t));
        return items.isEmpty() ? null : items.get(0);
    }

    private List<ThreadDto> toDtosFromEntities(List<Thread> threads) {
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

    private ThreadDto toDto(ThreadQueryView view) {
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

    public ThreadDto getById(Long id) {
        Thread t = threadRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在"));
        if (t.getStatus() == null || !"NORMAL".equals(t.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在");
        }
        ThreadQueryView view = threadRepository.findViewById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在"));
        if (view.getStatus() == null || !"NORMAL".equals(view.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在");
        }
        ThreadDto dto = toDto(view);
        // 服务端渲染并缓存 HTML，减轻前端开销
        String html = markdownRenderService.renderWithCache(t.getId(), t.getUpdatedAt(), t.getContentMd());
        dto.setContentHtml(html);
        return dto;
    }

    public ThreadDto update(Long userId, Long threadId, UpdateThreadRequest req) {
        Thread t = threadRepository.findById(threadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在"));
        if (t.getStatus() == null || !"NORMAL".equals(t.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在");
        }
        if (!t.getAuthorId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅作者可编辑帖子");
        }
        boolean changed = false;
        if (req.getTitle() != null) {
            String title = req.getTitle().trim();
            if (title.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题不能为空");
            }
            if (title.length() > 256) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题过长");
            }
            t.setTitle(title);
            changed = true;
        }
        if (req.getContent() != null) {
            String content = req.getContent();
            if (content.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容不能为空");
            }
            if (content.length() > 10000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容过长");
            }
            t.setContentMd(content);
            changed = true;
        }
        if (!changed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未提供需要更新的字段");
        }
        Thread saved = threadRepository.save(t);
        ThreadDto dto = toDto(saved);
        String html = markdownRenderService.renderWithCache(saved.getId(), saved.getUpdatedAt(), saved.getContentMd());
        dto.setContentHtml(html);
        return dto;
    }

    public void delete(Long userId, Long threadId) {
        Thread t = threadRepository.findById(threadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在"));
        if (!t.getAuthorId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅作者可删除帖子");
        }
        t.setStatus("DELETED");
        threadRepository.save(t);
    }
}
