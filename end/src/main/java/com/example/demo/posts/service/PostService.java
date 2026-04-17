package com.example.demo.posts.service;

import com.example.demo.posts.dto.CreatePostRequest;
import com.example.demo.posts.dto.PostDto;
import com.example.demo.posts.dto.PostQueryView;
import com.example.demo.posts.dto.UpdatePostRequest;
import com.example.demo.posts.entity.Post;
import com.example.demo.posts.repo.PostRepository;
import com.example.demo.threads.repo.ThreadRepository;
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

    public PostService(PostRepository postRepository, ThreadRepository threadRepository, UserRepository userRepository) {
        this.postRepository = postRepository;
        this.threadRepository = threadRepository;
        this.userRepository = userRepository;
    }

    public Page<PostDto> listByThread(Long threadId, int page, int size) {
        if (threadId == null || !threadRepository.existsById(threadId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在");
        }
        PageRequest pr = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 50));
        Page<PostQueryView> p = postRepository.findByThreadAscView(threadId, pr);
        return new org.springframework.data.domain.PageImpl<>(p.getContent().stream().map(this::toDto).toList(), p.getPageable(), p.getTotalElements());
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
        if (p == null) return null;
        return postRepository.findViewById(p.getId()).map(this::toDto).orElse(null);
    }

    private PostDto toDto(PostQueryView view) {
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
        return dto;
    }
}
