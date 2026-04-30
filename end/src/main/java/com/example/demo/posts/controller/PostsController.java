package com.example.demo.posts.controller;

import com.example.demo.common.SimpleRateLimiter;
import com.example.demo.posts.dto.CreatePostRequest;
import com.example.demo.posts.dto.PostLikeResponse;
import com.example.demo.posts.dto.PostDto;
import com.example.demo.posts.dto.UpdatePostRequest;
import com.example.demo.posts.service.PostService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class PostsController {
    private final PostService postService;
    private final SimpleRateLimiter rateLimiter;

    public PostsController(PostService postService, SimpleRateLimiter rateLimiter) {
        this.postService = postService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/threads/{threadId}/posts")
    public ResponseEntity<Map<String, Object>> listByThread(
            @PathVariable("threadId") Long threadId,
            @RequestParam(value = "sort", defaultValue = "time") String sort,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        Page<PostDto> p = postService.listByThread(threadId, getOptionalUserId(), sort, page, size);
        Map<String, Object> body = new HashMap<>();
        body.put("items", p.getContent());
        body.put("page", page);
        body.put("size", size);
        body.put("total", p.getTotalElements());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/posts/{id}/like")
    public ResponseEntity<PostLikeResponse> likeStatus(@PathVariable("id") Long id) {
        Long userId = getUserId();
        return ResponseEntity.ok(postService.likeStatus(userId, id));
    }

    @PostMapping("/posts/{id}/like")
    public ResponseEntity<PostLikeResponse> like(@PathVariable("id") Long id) {
        Long userId = getUserId();
        return ResponseEntity.ok(postService.like(userId, id));
    }

    @DeleteMapping("/posts/{id}/like")
    public ResponseEntity<PostLikeResponse> unlike(@PathVariable("id") Long id) {
        Long userId = getUserId();
        return ResponseEntity.ok(postService.unlike(userId, id));
    }

    @PostMapping("/threads/{threadId}/posts")
    public ResponseEntity<PostDto> create(
            @PathVariable("threadId") Long threadId,
            @Valid @RequestBody CreatePostRequest req
    ) {
        Long userId = getUserId();
        // 简单限速：60 秒内最多 20 次
        boolean allowed = rateLimiter.allow(userId, 60, 20);
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
        }
        PostDto dto = postService.create(userId, threadId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @PatchMapping("/posts/{id}")
    public ResponseEntity<PostDto> update(
            @PathVariable("id") Long id,
            @Valid @RequestBody UpdatePostRequest req
    ) {
        Long userId = getUserId();
        PostDto dto = postService.update(userId, id, req);
        return ResponseEntity.ok(dto);
    }

    @DeleteMapping("/posts/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable("id") Long id
    ) {
        Long userId = getUserId();
        postService.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    private Long getUserId() {
        Long userId = getOptionalUserId();
        if (userId != null) {
            return userId;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
    }

    private Long getOptionalUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Long) {
            return (Long) auth.getPrincipal();
        }
        return null;
    }
}
