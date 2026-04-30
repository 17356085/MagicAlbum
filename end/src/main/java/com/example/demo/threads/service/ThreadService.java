package com.example.demo.threads.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.demo.common.RedisKeys;
import com.example.demo.notifications.client.NotificationClient;
import com.example.demo.sections.repo.SectionRepository;
import com.example.demo.tags.service.TagService;
import com.example.demo.threads.dto.CreateThreadRequest;
import com.example.demo.threads.dto.ThreadLikeResponse;
import com.example.demo.threads.dto.ThreadQueryView;
import com.example.demo.threads.dto.ThreadRankingView;
import com.example.demo.threads.dto.UpdateThreadRequest;
import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.entity.ThreadLike;
import com.example.demo.threads.repo.ThreadLikeRepository;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.common.config.RabbitMQConfig;
import com.example.demo.user.repo.UserRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ThreadService {
    private static final Duration THREAD_LIST_CACHE_TTL = Duration.ofMinutes(2);
    private static final Duration THREAD_DETAIL_CACHE_TTL = Duration.ofMinutes(5);

    private final ThreadRepository threadRepository;
    private final ThreadLikeRepository threadLikeRepository;
    private final SectionRepository sectionRepository;
    private final UserRepository userRepository;
    private final MarkdownRenderService markdownRenderService;
    private final com.example.demo.threads.service.mp.ThreadReadServiceMp threadReadServiceMp;
    private final boolean mpThreadsEnabled;
    private final RabbitTemplate rabbitTemplate;
    private final boolean rabbitEnabled;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final NotificationClient notificationClient;
    private final TagService tagService;

    @Autowired
    public ThreadService(ThreadRepository threadRepository,
                         ThreadLikeRepository threadLikeRepository,
                         SectionRepository sectionRepository,
                         UserRepository userRepository,
                         MarkdownRenderService markdownRenderService,
                         ObjectProvider<com.example.demo.threads.service.mp.ThreadReadServiceMp> threadReadServiceMpProvider,
                         @Value("${feature.mp.read.threads:false}") boolean mpThreadsEnabled,
                         ObjectProvider<RabbitTemplate> rabbitTemplateProvider,
                         @Value("${app.rabbit.enabled:true}") boolean rabbitEnabled,
                         ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                         ObjectProvider<ObjectMapper> objectMapperProvider,
                         ObjectProvider<NotificationClient> notificationClientProvider,
                         ObjectProvider<TagService> tagServiceProvider) {
        this(threadRepository, threadLikeRepository, sectionRepository, userRepository, markdownRenderService,
                threadReadServiceMpProvider, mpThreadsEnabled, rabbitTemplateProvider, rabbitEnabled,
                redisTemplateProvider == null ? null : redisTemplateProvider.getIfAvailable(),
                resolveObjectMapper(objectMapperProvider),
                notificationClientProvider == null ? null : notificationClientProvider.getIfAvailable(),
                tagServiceProvider == null ? null : tagServiceProvider.getIfAvailable());
    }

    ThreadService(ThreadRepository threadRepository,
                  SectionRepository sectionRepository,
                  MarkdownRenderService markdownRenderService,
                  ObjectProvider<com.example.demo.threads.service.mp.ThreadReadServiceMp> threadReadServiceMpProvider,
                  boolean mpThreadsEnabled,
                  ObjectProvider<RabbitTemplate> rabbitTemplateProvider,
                  boolean rabbitEnabled) {
        this(threadRepository, null, sectionRepository, null, markdownRenderService,
                threadReadServiceMpProvider, mpThreadsEnabled, rabbitTemplateProvider, rabbitEnabled,
                null, defaultObjectMapper(), null, null);
    }

    ThreadService(ThreadRepository threadRepository,
                  ThreadLikeRepository threadLikeRepository,
                  SectionRepository sectionRepository,
                  UserRepository userRepository,
                  MarkdownRenderService markdownRenderService,
                  ObjectProvider<com.example.demo.threads.service.mp.ThreadReadServiceMp> threadReadServiceMpProvider,
                  boolean mpThreadsEnabled,
                  ObjectProvider<RabbitTemplate> rabbitTemplateProvider,
                  boolean rabbitEnabled,
                  StringRedisTemplate redisTemplate,
                  ObjectMapper objectMapper,
                  NotificationClient notificationClient,
                  TagService tagService) {
        this.threadRepository = threadRepository;
        this.threadLikeRepository = threadLikeRepository;
        this.sectionRepository = sectionRepository;
        this.userRepository = userRepository;
        this.markdownRenderService = markdownRenderService;
        this.threadReadServiceMp = threadReadServiceMpProvider.getIfAvailable();
        this.mpThreadsEnabled = mpThreadsEnabled;
        this.rabbitTemplate = rabbitTemplateProvider.getIfAvailable();
        this.rabbitEnabled = rabbitEnabled;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper == null ? defaultObjectMapper() : objectMapper;
        this.notificationClient = notificationClient;
        this.tagService = tagService;
    }

    ThreadService(ThreadRepository threadRepository,
                  SectionRepository sectionRepository,
                  MarkdownRenderService markdownRenderService,
                  ObjectProvider<com.example.demo.threads.service.mp.ThreadReadServiceMp> threadReadServiceMpProvider,
                  boolean mpThreadsEnabled,
                  ObjectProvider<RabbitTemplate> rabbitTemplateProvider,
                  boolean rabbitEnabled,
                  StringRedisTemplate redisTemplate,
                  ObjectMapper objectMapper) {
        this(threadRepository, null, sectionRepository, null, markdownRenderService,
                threadReadServiceMpProvider, mpThreadsEnabled, rabbitTemplateProvider, rabbitEnabled,
                redisTemplate, objectMapper, null, null);
    }

    @Transactional
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
        replaceTags(saved.getId(), req.getTags());

        // 触发异步摘要生成
        if (rabbitEnabled && rabbitTemplate != null) {
            try {
                rabbitTemplate.convertAndSend(RabbitMQConfig.QUEUE_THREAD_SUMMARY, saved.getId());
            } catch (Exception e) {
                System.err.println("Failed to send summary generation task: " + e.getMessage());
            }
        }
        evictThreadListCache();

        ThreadDto dto = toDto(saved);
        dto.setTags(normalizeTags(req.getTags()));
        return dto;
    }

    public Page<ThreadDto> list(String q, String tag, Long sectionId, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 10);
        int pageIndex = Math.max(page - 1, 0);
        String normalizedTag = (tag == null || tag.isBlank()) ? null : tag.trim();
        String cacheKey = threadListCacheKey(q, normalizedTag, sectionId, page, limit);
        CachedThreadPage cached = readThreadListCache(cacheKey, pageIndex, limit);
        if (cached != null) {
            return new PageImpl<>(cached.items(), PageRequest.of(pageIndex, limit), cached.total());
        }
        if (normalizedTag == null && mpThreadsEnabled && threadReadServiceMp != null) {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<Thread> mpPage = threadReadServiceMp.searchNewest(q, sectionId, page, limit);
            java.util.List<ThreadDto> items = toDtosFromEntities(mpPage.getRecords());
            Page<ThreadDto> pageResult = new org.springframework.data.domain.PageImpl<>(items, PageRequest.of(pageIndex, limit), mpPage.getTotal());
            writeThreadListCache(cacheKey, new CachedThreadPage(items, mpPage.getTotal()));
            return pageResult;
        }
        PageRequest pr = PageRequest.of(pageIndex, limit);
        Page<ThreadQueryView> p = threadRepository.searchNewestView(
                (q == null || q.isBlank()) ? null : q,
                normalizedTag,
                sectionId,
                pr
        );
        List<ThreadDto> items = p.getContent().stream().map(this::toDto).toList();
        Page<ThreadDto> pageResult = new org.springframework.data.domain.PageImpl<>(items, p.getPageable(), p.getTotalElements());
        writeThreadListCache(cacheKey, new CachedThreadPage(items, p.getTotalElements()));
        return pageResult;
    }

    public Page<ThreadDto> list(String q, Long sectionId, int page, int size) {
        return list(q, null, sectionId, page, size);
    }

    @Transactional(readOnly = true)
    public Page<ThreadDto> ranking(Long sectionId, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 20);
        int pageIndex = Math.max(page - 1, 0);
        Page<ThreadRankingView> p = threadRepository.rankingView(sectionId, PageRequest.of(pageIndex, limit));
        List<ThreadDto> items = p.getContent().stream().map(this::toDto).toList();
        return new PageImpl<>(items, p.getPageable(), p.getTotalElements());
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
        dto.setLikeCount(countLikes(view.getId()));
        dto.setTags(getThreadTags(view.getId()));
        return dto;
    }

    private ThreadDto toDto(ThreadRankingView view) {
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

    @Transactional
    public ThreadLikeResponse like(Long userId, Long threadId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        Thread thread = findNormalThread(threadId);
        boolean alreadyLiked = threadLikeRepository.existsByThreadIdAndUserId(threadId, userId);
        if (!alreadyLiked) {
            ThreadLike like = new ThreadLike();
            like.setThreadId(threadId);
            like.setUserId(userId);
            threadLikeRepository.save(like);
            evictThreadListCache();
            evictThreadDetailCache(threadId);
            sendLikeNotification(userId, thread);
        }
        return new ThreadLikeResponse(threadId, true, countLikes(threadId));
    }

    @Transactional
    public ThreadLikeResponse unlike(Long userId, Long threadId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        findNormalThread(threadId);
        if (threadLikeRepository.existsByThreadIdAndUserId(threadId, userId)) {
            threadLikeRepository.deleteByThreadIdAndUserId(threadId, userId);
            evictThreadListCache();
            evictThreadDetailCache(threadId);
        }
        return new ThreadLikeResponse(threadId, false, countLikes(threadId));
    }

    @Transactional(readOnly = true)
    public ThreadLikeResponse likeStatus(Long userId, Long threadId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        findNormalThread(threadId);
        return new ThreadLikeResponse(
                threadId,
                threadLikeRepository.existsByThreadIdAndUserId(threadId, userId),
                countLikes(threadId)
        );
    }

    public ThreadDto getById(Long id) {
        ThreadDto cached = readThreadDetailCache(id);
        if (cached != null) {
            return cached;
        }
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
        writeThreadDetailCache(id, dto);
        return dto;
    }

    private Thread findNormalThread(Long threadId) {
        Thread thread = threadRepository.findById(threadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在"));
        if (thread.getStatus() == null || !"NORMAL".equals(thread.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在");
        }
        return thread;
    }

    private long countLikes(Long threadId) {
        if (threadLikeRepository == null || threadId == null) {
            return 0;
        }
        try {
            return threadLikeRepository.countByThreadId(threadId);
        } catch (DataAccessException e) {
            return 0;
        }
    }

    private void sendLikeNotification(Long actorId, Thread thread) {
        if (notificationClient == null || userRepository == null || thread == null || thread.getAuthorId() == null) {
            return;
        }
        if (thread.getAuthorId().equals(actorId)) {
            return;
        }
        String actorName = userRepository.findById(actorId)
                .map(user -> user.getUsername() == null || user.getUsername().isBlank() ? "有人" : user.getUsername())
                .orElse("有人");
        String title = thread.getTitle() == null || thread.getTitle().isBlank() ? "你的帖子" : thread.getTitle();
        notificationClient.sendLike(
                thread.getAuthorId(),
                "你的帖子收到点赞",
                actorName + " 点赞了《" + title + "》",
                thread.getId(),
                thread.getId(),
                "thread"
        );
    }

    @Transactional
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
        if (req.getSectionId() != null && !req.getSectionId().equals(t.getSectionId())) {
            if (!sectionRepository.existsById(req.getSectionId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分区不存在");
            }
            t.setSectionId(req.getSectionId());
            changed = true;
        }
        if (req.getTags() != null) {
            changed = true;
        }
        if (!changed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未提供需要更新的字段");
        }
        Thread saved = threadRepository.save(t);
        if (req.getTags() != null) {
            replaceTags(saved.getId(), req.getTags());
        }
        ThreadDto dto = toDto(saved);
        String html = markdownRenderService.renderWithCache(saved.getId(), saved.getUpdatedAt(), saved.getContentMd());
        dto.setContentHtml(html);
        evictThreadListCache();
        evictThreadDetailCache(threadId);
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
        evictThreadListCache();
        evictThreadDetailCache(threadId);
    }

    private CachedThreadPage readThreadListCache(String key, int pageIndex, int limit) {
        if (redisTemplate == null) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, CachedThreadPage.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeThreadListCache(String key, CachedThreadPage payload) {
        if (redisTemplate == null || payload == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(payload), THREAD_LIST_CACHE_TTL);
        } catch (Exception ignored) {
        }
    }

    private void evictThreadListCache() {
        if (redisTemplate == null) {
            return;
        }
        try {
            java.util.Set<String> keys = redisTemplate.keys(RedisKeys.threadsListPattern());
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception ignored) {
        }
    }

    private ThreadDto readThreadDetailCache(Long threadId) {
        if (redisTemplate == null || threadId == null) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(threadDetailCacheKey(threadId));
            if (json == null || json.isBlank()) {
                return null;
            }
            ThreadDto dto = objectMapper.readValue(json, ThreadDto.class);
            if (dto == null || dto.getAuthorId() == null) {
                redisTemplate.delete(threadDetailCacheKey(threadId));
                return null;
            }
            return dto;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeThreadDetailCache(Long threadId, ThreadDto dto) {
        if (redisTemplate == null || threadId == null || dto == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(threadDetailCacheKey(threadId), objectMapper.writeValueAsString(dto), THREAD_DETAIL_CACHE_TTL);
        } catch (Exception ignored) {
        }
    }

    private void evictThreadDetailCache(Long threadId) {
        if (redisTemplate == null || threadId == null) {
            return;
        }
        try {
            redisTemplate.delete(threadDetailCacheKey(threadId));
        } catch (Exception ignored) {
        }
    }

    private void replaceTags(Long threadId, List<String> tags) {
        if (tagService != null) {
            tagService.replaceThreadTags(threadId, tags);
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

    private List<String> normalizeTags(List<String> tags) {
        if (tagService == null) {
            return List.of();
        }
        return tagService.normalizeTags(tags);
    }

    private String threadListCacheKey(String q, Long sectionId, int page, int limit) {
        return RedisKeys.threadsList(q, sectionId, page, limit);
    }

    private String threadListCacheKey(String q, String tag, Long sectionId, int page, int limit) {
        return RedisKeys.threadsList(q, tag, sectionId, page, limit);
    }

    private String threadDetailCacheKey(Long threadId) {
        return RedisKeys.threadDetail(threadId);
    }

    private static ObjectMapper defaultObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private static ObjectMapper resolveObjectMapper(ObjectProvider<ObjectMapper> provider) {
        ObjectMapper mapper = provider == null ? null : provider.getIfAvailable();
        return mapper == null ? defaultObjectMapper() : mapper;
    }

    private record CachedThreadPage(List<ThreadDto> items, long total) {
    }
}
