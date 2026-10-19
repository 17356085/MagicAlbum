package com.example.demo.threads.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.demo.ai.service.AiSummaryCacheService;
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
import com.example.demo.threads.event.entity.ContentEventType;
import com.example.demo.threads.event.service.ContentEventRecorder;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ThreadService {
    private static final int MAX_CONTENT_CHARS = 10_000;
    private static final Logger log = LoggerFactory.getLogger(ThreadService.class);

    private final ThreadRepository threadRepository;
    private final ThreadLikeRepository threadLikeRepository;
    private final SectionRepository sectionRepository;
    private final UserRepository userRepository;
    private final MarkdownRenderService markdownRenderService;
    private final com.example.demo.threads.service.mp.ThreadReadServiceMp threadReadServiceMp;
    private final boolean mpThreadsEnabled;
    private final RabbitTemplate rabbitTemplate;
    private final boolean rabbitEnabled;
    private final ThreadCache cache;
    private final ThreadDtoMapper dtoMapper;
    private final NotificationClient notificationClient;
    private final TagService tagService;
    private final AiSummaryCacheService aiSummaryCacheService;
    private final ContentEventRecorder contentEventRecorder;

    @Autowired
    public ThreadService(ThreadRepository threadRepository,
                         ThreadLikeRepository threadLikeRepository,
                         SectionRepository sectionRepository,
                         UserRepository userRepository,
                         MarkdownRenderService markdownRenderService,
                         ObjectProvider<com.example.demo.threads.service.mp.ThreadReadServiceMp> threadReadServiceMpProvider,
                         @Value("${feature.mp.read.threads:false}") boolean mpThreadsEnabled,
                         ObjectProvider<RabbitTemplate> rabbitTemplateProvider,
                         @Value("${app.rabbit.enabled:false}") boolean rabbitEnabled,
                         ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                         ObjectProvider<ObjectMapper> objectMapperProvider,
                         ObjectProvider<NotificationClient> notificationClientProvider,
                         ObjectProvider<TagService> tagServiceProvider,
                         ObjectProvider<AiSummaryCacheService> aiSummaryCacheServiceProvider,
                         ContentEventRecorder contentEventRecorder) {
        this(threadRepository, threadLikeRepository, sectionRepository, userRepository, markdownRenderService,
                threadReadServiceMpProvider, mpThreadsEnabled, rabbitTemplateProvider, rabbitEnabled,
                redisTemplateProvider == null ? null : redisTemplateProvider.getIfAvailable(),
                resolveObjectMapper(objectMapperProvider),
                notificationClientProvider == null ? null : notificationClientProvider.getIfAvailable(),
                tagServiceProvider == null ? null : tagServiceProvider.getIfAvailable(),
                aiSummaryCacheServiceProvider == null ? null : aiSummaryCacheServiceProvider.getIfAvailable(),
                contentEventRecorder);
    }

    ThreadService(ThreadRepository threadRepository,
                  SectionRepository sectionRepository,
                  MarkdownRenderService markdownRenderService,
                  ObjectProvider<com.example.demo.threads.service.mp.ThreadReadServiceMp> threadReadServiceMpProvider,
                  boolean mpThreadsEnabled,
                  ObjectProvider<RabbitTemplate> rabbitTemplateProvider,
                  boolean rabbitEnabled,
                  ContentEventRecorder contentEventRecorder) {
        this(threadRepository, null, sectionRepository, null, markdownRenderService,
                threadReadServiceMpProvider, mpThreadsEnabled, rabbitTemplateProvider, rabbitEnabled,
                null, defaultObjectMapper(), null, null, null, contentEventRecorder);
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
                  TagService tagService,
                  AiSummaryCacheService aiSummaryCacheService,
                  ContentEventRecorder contentEventRecorder) {
        this.threadRepository = threadRepository;
        this.threadLikeRepository = threadLikeRepository;
        this.sectionRepository = sectionRepository;
        this.userRepository = userRepository;
        this.markdownRenderService = markdownRenderService;
        this.threadReadServiceMp = threadReadServiceMpProvider.getIfAvailable();
        this.mpThreadsEnabled = mpThreadsEnabled;
        this.rabbitTemplate = rabbitTemplateProvider.getIfAvailable();
        this.rabbitEnabled = rabbitEnabled;
        this.cache = new ThreadCache(redisTemplate, objectMapper == null ? defaultObjectMapper() : objectMapper);
        this.notificationClient = notificationClient;
        this.tagService = tagService;
        this.aiSummaryCacheService = aiSummaryCacheService;
        this.contentEventRecorder = Objects.requireNonNull(contentEventRecorder, "contentEventRecorder");
        this.dtoMapper = new ThreadDtoMapper(threadRepository, threadLikeRepository, tagService);
    }

    ThreadService(ThreadRepository threadRepository,
                  SectionRepository sectionRepository,
                  MarkdownRenderService markdownRenderService,
                  ObjectProvider<com.example.demo.threads.service.mp.ThreadReadServiceMp> threadReadServiceMpProvider,
                  boolean mpThreadsEnabled,
                  ObjectProvider<RabbitTemplate> rabbitTemplateProvider,
                  boolean rabbitEnabled,
                  StringRedisTemplate redisTemplate,
                  ObjectMapper objectMapper,
                  ContentEventRecorder contentEventRecorder) {
        this(threadRepository, null, sectionRepository, null, markdownRenderService,
                threadReadServiceMpProvider, mpThreadsEnabled, rabbitTemplateProvider, rabbitEnabled,
                redisTemplate, objectMapper, null, null, null, contentEventRecorder);
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
        if (req.getContent().length() > MAX_CONTENT_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容过长");
        }

        Thread t = new Thread();
        t.setSectionId(sectionId);
        t.setAuthorId(authorId);
        t.setTitle(req.getTitle().trim());
        t.setContentMd(req.getContent());
        t.setContentVersion(1L);
        Thread saved = threadRepository.save(t);
        recordContentEvent(saved, ContentEventType.THREAD_UPSERTED);
        replaceTags(saved.getId(), req.getTags());

        ThreadDto dto = dtoMapper.toDto(saved);
        dto.setTags(normalizeTags(req.getTags()));
        afterCommit(() -> {
            cache.evictLists();
            publishSummaryGeneration(saved.getId());
        });
        return dto;
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }

    private void publishSummaryGeneration(Long threadId) {
        if (!rabbitEnabled || rabbitTemplate == null) {
            return;
        }
        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.QUEUE_THREAD_SUMMARY, threadId);
        } catch (Exception e) {
            log.warn("Failed to send summary generation task for thread {}: {}",
                    threadId, e.getClass().getSimpleName());
        }
    }

    public Page<ThreadDto> list(String q, String tag, Long sectionId, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 10);
        int pageIndex = Math.max(page, 1) - 1;
        String normalizedQuery = normalizeQuery(q);
        String normalizedTag = (tag == null || tag.isBlank()) ? null : tag.trim();
        String cacheKey = threadListCacheKey(normalizedQuery, normalizedTag, sectionId, page, limit);
        ThreadCache.CachedThreadPage cached = cache.readList(cacheKey);
        if (cached != null) {
            if (isFreshThreadPage(cached)) {
                return new PageImpl<>(cached.items(), PageRequest.of(pageIndex, limit), cached.total());
            }
            cache.evictList(cacheKey);
        }
        if (normalizedTag == null && mpThreadsEnabled && threadReadServiceMp != null) {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<Thread> mpPage = threadReadServiceMp.searchNewest(normalizedQuery, sectionId, page, limit);
            java.util.List<ThreadDto> items = dtoMapper.toDtosFromEntities(mpPage.getRecords());
            Page<ThreadDto> pageResult = new org.springframework.data.domain.PageImpl<>(items, PageRequest.of(pageIndex, limit), mpPage.getTotal());
            cache.writeList(cacheKey, new ThreadCache.CachedThreadPage(items, mpPage.getTotal()));
            return pageResult;
        }
        PageRequest pr = PageRequest.of(pageIndex, limit);
        Page<ThreadQueryView> p = threadRepository.searchNewestView(
                normalizedQuery,
                normalizedTag,
                sectionId,
                pr
        );
        List<ThreadDto> items = p.getContent().stream().map(dtoMapper::toDto).toList();
        Page<ThreadDto> pageResult = new org.springframework.data.domain.PageImpl<>(items, p.getPageable(), p.getTotalElements());
        cache.writeList(cacheKey, new ThreadCache.CachedThreadPage(items, p.getTotalElements()));
        return pageResult;
    }

    public Page<ThreadDto> list(String q, Long sectionId, int page, int size) {
        return list(q, null, sectionId, page, size);
    }

    @Transactional(readOnly = true)
    public Page<ThreadDto> listFollowing(Long userId, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 20);
        int pageIndex = Math.max(page, 1) - 1;
        Page<com.example.demo.threads.dto.ThreadFeedView> result = threadRepository.findFollowingFeed(
                userId, PageRequest.of(pageIndex, limit));
        return new PageImpl<>(result.getContent().stream().map(dtoMapper::toFeedDto).toList(),
                result.getPageable(), result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Page<ThreadDto> ranking(Long sectionId, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 20);
        int pageIndex = Math.max(page, 1) - 1;
        Page<ThreadRankingView> p = threadRepository.rankingView(sectionId, PageRequest.of(pageIndex, limit));
        List<ThreadDto> items = p.getContent().stream().map(dtoMapper::toDto).toList();
        return new PageImpl<>(items, p.getPageable(), p.getTotalElements());
    }

    public Page<ThreadDto> listByAuthor(Long authorId, String sort, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), 10));
        Page<ThreadQueryView> p;
        String s = (sort == null || sort.isBlank()) ? "updatedAt" : sort;
        if ("createdAt".equalsIgnoreCase(s)) {
            p = threadRepository.findByAuthorCreatedDescView(authorId, pr);
        } else {
            p = threadRepository.findByAuthorNewestView(authorId, pr);
        }
        return new org.springframework.data.domain.PageImpl<>(p.getContent().stream().map(dtoMapper::toDto).toList(), p.getPageable(), p.getTotalElements());
    }

    public Page<ThreadDto> listByAuthor(Long authorId, String q, Long sectionId, String sort, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), 10));
        String s = (sort == null || sort.isBlank()) ? "updatedAt" : sort;
        String query = (q == null || q.isBlank()) ? null : q.trim();
        Long sid = sectionId;
        Page<ThreadQueryView> p;
        if ("createdAt".equalsIgnoreCase(s)) {
            p = threadRepository.searchByAuthorCreatedDescView(authorId, query, sid, pr);
        } else {
            p = threadRepository.searchByAuthorUpdatedDescView(authorId, query, sid, pr);
        }
        return new org.springframework.data.domain.PageImpl<>(p.getContent().stream().map(dtoMapper::toDto).toList(), p.getPageable(), p.getTotalElements());
    }

    @Transactional
    public ThreadLikeResponse like(Long userId, Long threadId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        Thread thread = findNormalThreadForInteraction(threadId);
        boolean alreadyLiked = threadLikeRepository.existsByThreadIdAndUserId(threadId, userId);
        if (!alreadyLiked) {
            ThreadLike like = new ThreadLike();
            like.setThreadId(threadId);
            like.setUserId(userId);
            threadLikeRepository.save(like);
            evictThreadCachesAfterCommit(threadId);
            sendLikeNotification(userId, thread);
        }
        return new ThreadLikeResponse(threadId, true, dtoMapper.countLikes(threadId));
    }

    @Transactional
    public ThreadLikeResponse unlike(Long userId, Long threadId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        findNormalThreadForInteraction(threadId);
        if (threadLikeRepository.existsByThreadIdAndUserId(threadId, userId)) {
            threadLikeRepository.deleteByThreadIdAndUserId(threadId, userId);
            evictThreadCachesAfterCommit(threadId);
        }
        return new ThreadLikeResponse(threadId, false, dtoMapper.countLikes(threadId));
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
                dtoMapper.countLikes(threadId)
        );
    }

    public ThreadDto getById(Long id) {
        // A moderation decision must take effect even if a detail response was cached earlier.
        Thread t = findNormalThread(id);
        ThreadDto cached = cache.readDetail(id);
        if (cached != null && t.getUpdatedAt() != null
                && Objects.equals(t.getUpdatedAt(), cached.getUpdatedAt())) {
            return cached;
        }
        if (cached != null) {
            cache.evictDetail(id);
        }
        ThreadQueryView view = threadRepository.findViewById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在"));
        if (view.getStatus() == null || !"NORMAL".equals(view.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在");
        }
        ThreadDto dto = dtoMapper.toDto(view);
        // 服务端渲染并缓存 HTML，减轻前端开销
        String html = markdownRenderService.renderWithCache(view.getId(), view.getUpdatedAt(), view.getContent());
        dto.setContentHtml(html);
        cache.writeDetail(id, dto);
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

    private Thread findNormalThreadForInteraction(Long threadId) {
        Thread thread = threadRepository.findByIdForInteraction(threadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在"));
        if (thread.getStatus() == null || !"NORMAL".equals(thread.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在");
        }
        return thread;
    }

    private boolean isFreshThreadPage(ThreadCache.CachedThreadPage cached) {
        List<ThreadDto> items = cached.items();
        if (items == null || items.isEmpty()) {
            return true;
        }
        if (items.stream().anyMatch(dto -> dto == null
                || dto.getId() == null
                || dto.getId() <= 0
                || dto.getUpdatedAt() == null)) {
            return false;
        }
        List<Long> ids = items.stream().map(ThreadDto::getId).toList();
        if (ids.stream().distinct().count() != ids.size()) {
            return false;
        }
        List<Thread> authoritative = threadRepository.findAllById(ids);
        if (authoritative == null || authoritative.size() != ids.size()) {
            return false;
        }
        Map<Long, Thread> byId = new HashMap<>();
        for (Thread thread : authoritative) {
            if (thread == null || thread.getId() == null || byId.put(thread.getId(), thread) != null) {
                return false;
            }
        }
        return items.stream().allMatch(dto -> {
            Thread thread = dto == null ? null : byId.get(dto.getId());
            return thread != null
                    && "NORMAL".equals(thread.getStatus())
                    && "NORMAL".equals(dto.getStatus())
                    && thread.getUpdatedAt() != null
                    && dto.getUpdatedAt() != null
                    && Objects.equals(thread.getUpdatedAt(), dto.getUpdatedAt());
        });
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
        Thread t = threadRepository.findByIdForInteraction(threadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在"));
        if (t.getStatus() == null || !"NORMAL".equals(t.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在");
        }
        if (!t.getAuthorId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅作者可编辑帖子");
        }
        boolean changed = false;
        boolean contentChanged = false;
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
            if (content.length() > MAX_CONTENT_CHARS) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容过长");
            }
            contentChanged = !Objects.equals(t.getContentMd(), content);
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
        if (contentChanged) {
            t.setSummary(null);
            t.setSummaryStatus("PENDING");
            t.setSummaryOwner(null);
            t.setSummaryLeaseUntil(null);
            t.setSummaryAttempts(0);
        }
        advanceContentVersion(t);
        Thread saved = threadRepository.save(t);
        recordContentEvent(saved, ContentEventType.THREAD_UPSERTED);
        if (req.getTags() != null) {
            replaceTags(saved.getId(), req.getTags());
        }
        ThreadDto dto = dtoMapper.toDto(saved);
        String html = markdownRenderService.renderWithCache(saved.getId(), saved.getUpdatedAt(), saved.getContentMd());
        dto.setContentHtml(html);
        evictThreadCachesAfterCommit(threadId, contentChanged);
        return dto;
    }

    @Transactional
    public void delete(Long userId, Long threadId) {
        Thread t = threadRepository.findByIdForInteraction(threadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在"));
        if (!t.getAuthorId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅作者可删除帖子");
        }
        if ("DELETED".equals(t.getStatus())) {
            return;
        }
        t.setStatus("DELETED");
        advanceContentVersion(t);
        threadRepository.save(t);
        recordContentEvent(t, ContentEventType.THREAD_DELETED);
        evictThreadCachesAfterCommit(threadId);
    }

    @Transactional
    public void hideForModeration(Long threadId) {
        Thread thread = findNormalThreadForInteraction(threadId);
        thread.setStatus("HIDDEN");
        advanceContentVersion(thread);
        threadRepository.save(thread);
        recordContentEvent(thread, ContentEventType.THREAD_HIDDEN);
        evictThreadCachesAfterCommit(threadId);
    }

    private void advanceContentVersion(Thread thread) {
        thread.setContentVersion(Math.addExact(thread.getContentVersion(), 1L));
    }

    private void recordContentEvent(Thread thread, ContentEventType eventType) {
        contentEventRecorder.record(thread.getId(), thread.getContentVersion(), eventType);
    }

    private void replaceTags(Long threadId, List<String> tags) {
        if (tagService != null) {
            tagService.replaceThreadTags(threadId, tags);
        }
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tagService == null) {
            return List.of();
        }
        return tagService.normalizeTags(tags);
    }

    private String threadListCacheKey(String q, String tag, Long sectionId, int page, int limit) {
        return RedisKeys.threadsList(q, tag, sectionId, page, limit);
    }

    private String normalizeQuery(String q) {
        return q == null || q.isBlank() ? null : q.trim();
    }

    private void evictThreadCachesAfterCommit(Long threadId) {
        evictThreadCachesAfterCommit(threadId, false);
    }

    private void evictThreadCachesAfterCommit(Long threadId, boolean republishSummary) {
        afterCommit(() -> {
            cache.evictLists();
            cache.evictDetail(threadId);
            markdownRenderService.evict(threadId);
            if (aiSummaryCacheService != null) {
                aiSummaryCacheService.evict(threadId);
            }
            if (republishSummary) {
                publishSummaryGeneration(threadId);
            }
        });
    }

    private static ObjectMapper defaultObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private static ObjectMapper resolveObjectMapper(ObjectProvider<ObjectMapper> provider) {
        ObjectMapper mapper = provider == null ? null : provider.getIfAvailable();
        return mapper == null ? defaultObjectMapper() : mapper;
    }

}
