package com.example.demo.posts.service;

import com.example.demo.posts.dto.CreatePostRequest;
import com.example.demo.posts.dto.PostDto;
import com.example.demo.posts.dto.UpdatePostRequest;
import com.example.demo.posts.entity.Post;
import com.example.demo.posts.repo.PostRepository;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.user.service.UserProfileService;
import com.example.demo.user.repo.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PostService {
    private final PostRepository postRepository;
    private final ThreadRepository threadRepository;
    private final UserRepository userRepository;
    private final UserProfileService userProfileService;

    public PostService(PostRepository postRepository, ThreadRepository threadRepository, UserRepository userRepository, UserProfileService userProfileService) {
        this.postRepository = postRepository;
        this.threadRepository = threadRepository;
        this.userRepository = userRepository;
        this.userProfileService = userProfileService;
    }

    public Page<PostDto> listByThread(Long threadId, int page, int size) {
        if (threadId == null || !threadRepository.existsById(threadId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在");
        }
        PageRequest pr = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 50));
        Page<Post> p = postRepository.findByThreadAsc(threadId, pr);
        return p.map(this::toDto);
    }

    public Page<PostDto> listByAuthor(Long authorId, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 50));
        Page<Post> p = postRepository.findByAuthorDesc(authorId, pr);
        return p.map(this::toDto);
    }

    public Page<PostDto> listByAuthor(Long authorId, String q, Long sectionId, String sort, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 50));
        String s = (sort == null || sort.isBlank()) ? "createdAt" : sort.trim();
        String keyword = (q == null || q.isBlank()) ? null : q.trim();
        Page<Post> p;
        if (keyword == null) {
            if ("updatedAt".equalsIgnoreCase(s)) {
                p = postRepository.findByAuthorUpdatedDescWithSection(authorId, sectionId, pr);
            } else {
                p = postRepository.findByAuthorCreatedDescWithSection(authorId, sectionId, pr);
            }
        } else {
            if ("updatedAt".equalsIgnoreCase(s)) {
                p = postRepository.searchByAuthorUpdatedDescWithSection(authorId, keyword, sectionId, pr);
            } else {
                p = postRepository.searchByAuthorCreatedDescWithSection(authorId, keyword, sectionId, pr);
            }
        }
        return p.map(this::toDto);
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
        String content = req.getContentMd();
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容不能为空");
        }
        if (content.length() > 3000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容过长");
        }
        Long parentId = req.getReplyToPostId();
        if (parentId != null) {
            Post parent = postRepository.findActiveById(parentId)
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
        return toDto(saved);
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
        return toDto(saved);
    }

    public void delete(Long userId, Long postId) {
        Post post = postRepository.findActiveById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "评论不存在"));
        if (!post.getAuthorId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅作者可删除评论");
        }
        postRepository.deleteById(postId);
    }

    private PostDto toDto(Post p) {
        PostDto dto = new PostDto();
        dto.setId(p.getId());
        dto.setThreadId(p.getThreadId());
        // 帖子标题用于在“我的评论”页面显示所属帖子更直观的名称
        try {
            String title = threadRepository.findById(p.getThreadId()).map(t -> t.getTitle()).orElse(null);
            dto.setThreadTitle(title);
        } catch (Exception ignored) {}
        dto.setAuthorId(p.getAuthorId());
        dto.setAuthorUsername(userRepository.findById(p.getAuthorId()).map(u -> u.getUsername()).orElse(null));
        // 从用户资料服务获取头像 URL（允许为空字符串）
        try {
            String avatar = userProfileService.getProfile(p.getAuthorId()).getAvatarUrl();
            dto.setAuthorAvatarUrl(avatar == null ? "" : avatar);
        } catch (Exception ignored) {
            dto.setAuthorAvatarUrl("");
        }
        dto.setContent(p.getContentMd());
        dto.setReplyToPostId(p.getReplyToPostId());
        dto.setCreatedAt(p.getCreatedAt());
        dto.setUpdatedAt(p.getUpdatedAt());
        return dto;
    }
}