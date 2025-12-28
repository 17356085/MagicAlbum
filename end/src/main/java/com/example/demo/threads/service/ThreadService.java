package com.example.demo.threads.service;

import com.example.demo.sections.repo.SectionRepository;
import com.example.demo.user.repo.UserRepository;
import com.example.demo.threads.dto.CreateThreadRequest;
import com.example.demo.threads.dto.UpdateThreadRequest;
import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ThreadService {
    private final ThreadRepository threadRepository;
    private final SectionRepository sectionRepository;
    private final UserRepository userRepository;
    private final MarkdownRenderService markdownRenderService;
    private final com.example.demo.threads.service.mp.ThreadReadServiceMp threadReadServiceMp;
    private final boolean mpThreadsEnabled;

    public ThreadService(ThreadRepository threadRepository, SectionRepository sectionRepository, UserRepository userRepository, MarkdownRenderService markdownRenderService,
                         ObjectProvider<com.example.demo.threads.service.mp.ThreadReadServiceMp> threadReadServiceMpProvider,
                         @Value("${feature.mp.read.threads:false}") boolean mpThreadsEnabled) {
        this.threadRepository = threadRepository;
        this.sectionRepository = sectionRepository;
        this.userRepository = userRepository;
        this.markdownRenderService = markdownRenderService;
        this.threadReadServiceMp = threadReadServiceMpProvider.getIfAvailable();
        this.mpThreadsEnabled = mpThreadsEnabled;
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

        ThreadDto dto = new ThreadDto();
        dto.setId(saved.getId());
        dto.setSectionId(saved.getSectionId());
        dto.setSectionName(sectionRepository.findById(saved.getSectionId()).map(s -> s.getName()).orElse(null));
        dto.setAuthorId(saved.getAuthorId());
        dto.setAuthorUsername(userRepository.findById(saved.getAuthorId()).map(u -> u.getUsername()).orElse(null));
        dto.setTitle(saved.getTitle());
        dto.setContent(saved.getContentMd());
        dto.setStatus(saved.getStatus());
        dto.setCreatedAt(saved.getCreatedAt());
        dto.setUpdatedAt(saved.getUpdatedAt());
        return dto;
    }

    public Page<ThreadDto> list(String q, Long sectionId, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 10);
        int pageIndex = Math.max(page - 1, 0);
        if (mpThreadsEnabled && threadReadServiceMp != null) {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<Thread> mpPage = threadReadServiceMp.searchNewest(q, sectionId, page, limit);
            java.util.List<ThreadDto> items = mpPage.getRecords().stream().map(this::toDto).toList();
            return new org.springframework.data.domain.PageImpl<>(items, PageRequest.of(pageIndex, limit), mpPage.getTotal());
        }
        PageRequest pr = PageRequest.of(pageIndex, limit);
        Page<Thread> p = threadRepository.searchNewest(
                (q == null || q.isBlank()) ? null : q,
                sectionId,
                pr
        );
        return p.map(this::toDto);
    }

    public Page<ThreadDto> listByAuthor(Long authorId, String sort, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 10));
        Page<Thread> p;
        String s = (sort == null || sort.isBlank()) ? "updatedAt" : sort;
        if ("createdAt".equalsIgnoreCase(s)) {
            p = threadRepository.findByAuthorCreatedDesc(authorId, pr);
        } else {
            p = threadRepository.findByAuthorNewest(authorId, pr);
        }
        return p.map(this::toDto);
    }

    public Page<ThreadDto> listByAuthor(Long authorId, String q, Long sectionId, String sort, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 10));
        String s = (sort == null || sort.isBlank()) ? "updatedAt" : sort;
        String query = (q == null || q.isBlank()) ? null : q.trim();
        Long sid = sectionId;
        Page<Thread> p;
        if ("createdAt".equalsIgnoreCase(s)) {
            p = threadRepository.searchByAuthorCreatedDesc(authorId, query, sid, pr);
        } else {
            p = threadRepository.searchByAuthorUpdatedDesc(authorId, query, sid, pr);
        }
        return p.map(this::toDto);
    }

    private ThreadDto toDto(Thread t) {
        ThreadDto dto = new ThreadDto();
        dto.setId(t.getId());
        dto.setSectionId(t.getSectionId());
        dto.setSectionName(sectionRepository.findById(t.getSectionId()).map(s -> s.getName()).orElse(null));
        dto.setAuthorId(t.getAuthorId());
        dto.setAuthorUsername(userRepository.findById(t.getAuthorId()).map(u -> u.getUsername()).orElse(null));
        dto.setTitle(t.getTitle());
        dto.setContent(t.getContentMd());
        dto.setStatus(t.getStatus());
        dto.setCreatedAt(t.getCreatedAt());
        dto.setUpdatedAt(t.getUpdatedAt());
        return dto;
    }

    public ThreadDto getById(Long id) {
        Thread t = threadRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在"));
        if (t.getStatus() == null || !"NORMAL".equals(t.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在");
        }
        ThreadDto dto = toDto(t);
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