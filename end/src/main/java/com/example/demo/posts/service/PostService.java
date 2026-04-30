package com.example.demo.posts.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.demo.common.RedisKeys;
import com.example.demo.notifications.client.NotificationClient;
import com.example.demo.posts.dto.CreatePostRequest;
import com.example.demo.posts.dto.PostLikeResponse;
import com.example.demo.posts.dto.PostDto;
import com.example.demo.posts.dto.PostQueryView;
import com.example.demo.posts.dto.UpdatePostRequest;
import com.example.demo.posts.entity.Post;
import com.example.demo.posts.entity.PostLike;
import com.example.demo.posts.repo.PostLikeRepository;
import com.example.demo.posts.repo.PostRepository;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.user.repo.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PostService {
    private static final Duration THREAD_POSTS_CACHE_TTL = Duration.ofMinutes(2);
    private static final Pattern MENTION_PATTERN = Pattern.compile("(?<![\\w.])@([A-Za-z0-9_\\-]{3,64})");

    private final PostRepository postRepository;
    private final PostLikeRepository postLikeRepository;
    private final ThreadRepository threadRepository;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final NotificationClient notificationClient;

    @Autowired
    public PostService(PostRepository postRepository,
                       PostLikeRepository postLikeRepository,
                       ThreadRepository threadRepository,
                       UserRepository userRepository,
                       ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                       ObjectProvider<ObjectMapper> objectMapperProvider,
                       ObjectProvider<NotificationClient> notificationClientProvider) {
        this(postRepository, postLikeRepository, threadRepository, userRepository,
                redisTemplateProvider == null ? null : redisTemplateProvider.getIfAvailable(),
                resolveObjectMapper(objectMapperProvider),
                notificationClientProvider == null ? null : notificationClientProvider.getIfAvailable());
    }

    PostService(PostRepository postRepository, ThreadRepository threadRepository, UserRepository userRepository) {
        this(postRepository, null, threadRepository, userRepository, null, defaultObjectMapper(), null);
    }

    PostService(PostRepository postRepository, PostLikeRepository postLikeRepository,
                ThreadRepository threadRepository, UserRepository userRepository,
                StringRedisTemplate redisTemplate, ObjectMapper objectMapper, NotificationClient notificationClient) {
        this.postRepository = postRepository;
        this.postLikeRepository = postLikeRepository;
        this.threadRepository = threadRepository;
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper == null ? defaultObjectMapper() : objectMapper;
        this.notificationClient = notificationClient;
    }

    PostService(PostRepository postRepository, ThreadRepository threadRepository, UserRepository userRepository,
                StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this(postRepository, null, threadRepository, userRepository, redisTemplate, objectMapper, null);
    }

    public Page<PostDto> listByThread(Long threadId, int page, int size) {
        return listByThread(threadId, null, "time", page, size);
    }

    public Page<PostDto> listByThread(Long threadId, Long currentUserId, String sort, int page, int size) {
        if (threadId == null || !threadRepository.existsById(threadId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在");
        }
        int limit = Math.min(Math.max(size, 1), 50);
        int pageIndex = Math.max(page - 1, 0);
        String normalizedSort = normalizeSort(sort);
        CachedPostPage cached = readThreadPostsCache(threadId, normalizedSort, page, limit);
        if (cached != null) {
            return new PageImpl<>(cached.items(), PageRequest.of(pageIndex, limit), cached.total());
        }
        PageRequest pr = PageRequest.of(pageIndex, limit);
        Page<PostQueryView> p = "likeCount".equals(normalizedSort)
                ? postRepository.findByThreadLikeCountDescView(threadId, pr)
                : postRepository.findByThreadAscView(threadId, pr);
        List<PostDto> items = p.getContent().stream().map(view -> toDto(view, currentUserId)).toList();
        Page<PostDto> result = new org.springframework.data.domain.PageImpl<>(items, p.getPageable(), p.getTotalElements());
        if (currentUserId == null) {
            writeThreadPostsCache(threadId, normalizedSort, page, limit, new CachedPostPage(items, p.getTotalElements()));
        }
        return result;
    }

    public Page<PostDto> listByAuthor(Long authorId, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 50));
        Page<PostQueryView> p = postRepository.searchByAuthorCreatedDescWithSectionView(authorId, null, null, pr);
        return new org.springframework.data.domain.PageImpl<>(p.getContent().stream().map(this::toDto).toList(), p.getPageable(), p.getTotalElements());
    }

    public Page<PostDto> listByAuthor(Long authorId, String q, Long sectionId, String sort, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 50));
        String s = (sort == null || sort.isBlank()) ? "createdAt" : sort.trim();
        String keyword = (q == null || q.isBlank()) ? null : q.trim();
        Page<PostQueryView> p;
        if (keyword == null) {
            if ("updatedAt".equalsIgnoreCase(s)) {
                p = postRepository.searchByAuthorUpdatedDescWithSectionView(authorId, null, sectionId, pr);
            } else {
                p = postRepository.searchByAuthorCreatedDescWithSectionView(authorId, null, sectionId, pr);
            }
        } else {
            if ("updatedAt".equalsIgnoreCase(s)) {
                p = postRepository.searchByAuthorUpdatedDescWithSectionView(authorId, keyword, sectionId, pr);
            } else {
                p = postRepository.searchByAuthorCreatedDescWithSectionView(authorId, keyword, sectionId, pr);
            }
        }
        return new org.springframework.data.domain.PageImpl<>(p.getContent().stream().map(this::toDto).toList(), p.getPageable(), p.getTotalElements());
    }

    public PostDto create(Long authorId, Long threadId, CreatePostRequest req) {
        if (authorId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌缺失");
        }
        // 防止数据库外键约束触发 500，将不存在的用户转为 401 友好错误
        if (!userRepository.existsById(authorId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在或已注销");
        }
        if (threadId == null || !threadRepository.existsById(threadId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在");
        }
        com.example.demo.threads.entity.Thread thread = threadRepository.findById(threadId).orElse(null);
        String content = req.getContentMd();
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容不能为空");
        }
        if (content.length() > 3000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容过长");
        }
        Long parentId = req.getReplyToPostId();
        Post parent = null;
        if (parentId != null) {
            parent = postRepository.findActiveById(parentId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "父评论不存在或已删除"));
            if (!parent.getThreadId().equals(threadId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "父评论不属于该帖子");
            }
            // 允许多层回复：不再限制父评论必须为顶层
        }

        Post post = new Post();
        post.setThreadId(threadId);
        post.setAuthorId(authorId);
        post.setContentMd(content);
        post.setReplyToPostId(parentId);
        Post saved = postRepository.save(post);
        evictThreadPostsCache(threadId);
        sendInteractionNotifications(authorId, thread, parent, saved);
        return toDto(saved);
    }

    private void sendInteractionNotifications(Long authorId, com.example.demo.threads.entity.Thread thread, Post parent, Post saved) {
        if (notificationClient == null || thread == null || saved == null) {
            return;
        }
        String actorName = userRepository.findById(authorId)
                .map(user -> user.getUsername() == null || user.getUsername().isBlank() ? "有人" : user.getUsername())
                .orElse("有人");
        String threadTitle = thread.getTitle() == null || thread.getTitle().isBlank() ? "你的帖子" : thread.getTitle();
        Set<Long> replyRecipients = new LinkedHashSet<>();
        if (thread.getAuthorId() != null && !thread.getAuthorId().equals(authorId)) {
            replyRecipients.add(thread.getAuthorId());
        }
        if (parent != null && parent.getAuthorId() != null && !parent.getAuthorId().equals(authorId)) {
            replyRecipients.add(parent.getAuthorId());
        }
        for (Long recipientId : replyRecipients) {
            notificationClient.sendReply(
                    recipientId,
                    "有人回复了你",
                    actorName + " 在《" + threadTitle + "》中回复了你",
                    thread.getId(),
                    saved.getId()
            );
        }

        for (Long mentionedUserId : resolveMentionedUserIds(saved.getContentMd())) {
            if (mentionedUserId.equals(authorId) || replyRecipients.contains(mentionedUserId)) {
                continue;
            }
            notificationClient.sendMention(
                    mentionedUserId,
                    "有人提到了你",
                    actorName + " 在《" + threadTitle + "》中提到了你",
                    thread.getId(),
                    saved.getId()
            );
        }
    }

    private Set<Long> resolveMentionedUserIds(String content) {
        Set<Long> userIds = new LinkedHashSet<>();
        if (content == null || content.isBlank()) {
            return userIds;
        }
        Matcher matcher = MENTION_PATTERN.matcher(content);
        while (matcher.find()) {
            String username = matcher.group(1);
            Optional<com.example.demo.user.entity.User> user = userRepository.findByUsername(username);
            user.map(com.example.demo.user.entity.User::getId).ifPresent(userIds::add);
        }
        return userIds;
    }

    public PostDto update(Long userId, Long postId, UpdatePostRequest req) {
        Post post = postRepository.findActiveById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "评论不存在"));
        if (!post.getAuthorId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅作者可编辑评论");
        }
        String content = req.getContentMd();
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容不能为空");
        }
        if (content.length() > 3000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容过长");
        }
        post.setContentMd(content);
        Post saved = postRepository.save(post);
        evictThreadPostsCache(post.getThreadId());
        return toDto(saved);
    }

    public void delete(Long userId, Long postId) {
        Post post = postRepository.findActiveById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "评论不存在"));
        if (!post.getAuthorId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅作者可删除评论");
        }
        evictThreadPostsCache(post.getThreadId());
        postRepository.deleteById(postId);
    }

    private PostDto toDto(Post p) {
        if (p == null) return null;
        return postRepository.findViewById(p.getId()).map(this::toDto).orElse(null);
    }

    private PostDto toDto(PostQueryView view) {
        return toDto(view, null);
    }

    private PostDto toDto(PostQueryView view, Long currentUserId) {
        PostDto dto = new PostDto();
        dto.setId(view.getId());
        dto.setThreadId(view.getThreadId());
        dto.setThreadTitle(view.getThreadTitle());
        dto.setAuthorId(view.getAuthorId());
        dto.setAuthorUsername(view.getAuthorUsername());
        dto.setAuthorNickname(view.getAuthorNickname());
        dto.setAuthorAvatarUrl(view.getAuthorAvatarUrl() == null ? "" : view.getAuthorAvatarUrl());
        dto.setContent(view.getContent());
        dto.setReplyToPostId(view.getReplyToPostId());
        dto.setParentAuthorId(view.getParentAuthorId());
        dto.setParentAuthorUsername(view.getParentAuthorUsername());
        dto.setParentAuthorNickname(view.getParentAuthorNickname());
        dto.setCreatedAt(view.getCreatedAt());
        dto.setUpdatedAt(view.getUpdatedAt());
        dto.setLikeCount(countLikes(view.getId()));
        dto.setLiked(currentUserId != null && isLikedBy(view.getId(), currentUserId));
        return dto;
    }

    @Transactional
    public PostLikeResponse like(Long userId, Long postId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        Post post = findActivePost(postId);
        boolean alreadyLiked = postLikeRepository.existsByPostIdAndUserId(postId, userId);
        if (!alreadyLiked) {
            PostLike like = new PostLike();
            like.setPostId(postId);
            like.setUserId(userId);
            postLikeRepository.save(like);
            evictThreadPostsCache(post.getThreadId());
            sendLikeNotification(userId, post);
        }
        return new PostLikeResponse(postId, true, countLikes(postId));
    }

    @Transactional
    public PostLikeResponse unlike(Long userId, Long postId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        Post post = findActivePost(postId);
        if (postLikeRepository.existsByPostIdAndUserId(postId, userId)) {
            postLikeRepository.deleteByPostIdAndUserId(postId, userId);
            evictThreadPostsCache(post.getThreadId());
        }
        return new PostLikeResponse(postId, false, countLikes(postId));
    }

    @Transactional(readOnly = true)
    public PostLikeResponse likeStatus(Long userId, Long postId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        findActivePost(postId);
        return new PostLikeResponse(postId, isLikedBy(postId, userId), countLikes(postId));
    }

    private Post findActivePost(Long postId) {
        return postRepository.findActiveById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "评论不存在"));
    }

    private long countLikes(Long postId) {
        if (postLikeRepository == null || postId == null) {
            return 0;
        }
        try {
            return postLikeRepository.countByPostId(postId);
        } catch (DataAccessException e) {
            return 0;
        }
    }

    private boolean isLikedBy(Long postId, Long userId) {
        if (postLikeRepository == null || postId == null || userId == null) {
            return false;
        }
        try {
            return postLikeRepository.existsByPostIdAndUserId(postId, userId);
        } catch (DataAccessException e) {
            return false;
        }
    }

    private void sendLikeNotification(Long actorId, Post post) {
        if (notificationClient == null || post == null || post.getAuthorId() == null || post.getAuthorId().equals(actorId)) {
            return;
        }
        String actorName = userRepository.findById(actorId)
                .map(user -> user.getUsername() == null || user.getUsername().isBlank() ? "有人" : user.getUsername())
                .orElse("有人");
        String threadTitle = threadRepository.findById(post.getThreadId())
                .map(thread -> thread.getTitle() == null || thread.getTitle().isBlank() ? "帖子" : thread.getTitle())
                .orElse("帖子");
        notificationClient.sendLike(
                post.getAuthorId(),
                "你的评论收到点赞",
                actorName + " 点赞了你在《" + threadTitle + "》中的评论",
                post.getThreadId(),
                post.getId(),
                "post"
        );
    }

    private CachedPostPage readThreadPostsCache(Long threadId, String sort, int page, int size) {
        if (redisTemplate == null || threadId == null) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(threadPostsCacheKey(threadId, sort, page, size));
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, CachedPostPage.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeThreadPostsCache(Long threadId, String sort, int page, int size, CachedPostPage payload) {
        if (redisTemplate == null || threadId == null || payload == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(threadPostsCacheKey(threadId, sort, page, size), objectMapper.writeValueAsString(payload), THREAD_POSTS_CACHE_TTL);
        } catch (Exception ignored) {
        }
    }

    private void evictThreadPostsCache(Long threadId) {
        if (redisTemplate == null || threadId == null) {
            return;
        }
        try {
            java.util.Set<String> keys = redisTemplate.keys(RedisKeys.threadPostsPattern(threadId));
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception ignored) {
        }
    }

    private String threadPostsCacheKey(Long threadId, String sort, int page, int size) {
        return RedisKeys.threadPosts(threadId, sort, page, size);
    }

    private String normalizeSort(String sort) {
        return "likeCount".equals(sort) ? "likeCount" : "time";
    }

    private static ObjectMapper defaultObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private static ObjectMapper resolveObjectMapper(ObjectProvider<ObjectMapper> provider) {
        ObjectMapper mapper = provider == null ? null : provider.getIfAvailable();
        return mapper == null ? defaultObjectMapper() : mapper;
    }

    private record CachedPostPage(List<PostDto> items, long total) {
    }
}
