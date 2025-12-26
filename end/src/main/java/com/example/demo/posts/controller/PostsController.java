package com.example.demo.posts.controller;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.common.SimpleRateLimiter;
import com.example.demo.posts.dto.CreatePostRequest;
import com.example.demo.posts.dto.PostDto;
import com.example.demo.posts.dto.UpdatePostRequest;
import com.example.demo.posts.service.PostService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class PostsController {
    private final PostService postService;
    private final JwtTokenProvider jwtTokenProvider;
    private final SimpleRateLimiter rateLimiter;

    public PostsController(PostService postService, JwtTokenProvider jwtTokenProvider, SimpleRateLimiter rateLimiter) {
        this.postService = postService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/threads/{threadId}/posts")
    public ResponseEntity<Map<String, Object>> listByThread(
            @PathVariable("threadId") Long threadId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        Page<PostDto> p = postService.listByThread(threadId, page, size);
        Map<String, Object> body = new HashMap<>();
        body.put("items", p.getContent());
        body.put("page", page);
        body.put("size", size);
        body.put("total", p.getTotalElements());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/threads/{threadId}/posts")
    public ResponseEntity<PostDto> create(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @PathVariable("threadId") Long threadId,
            @Valid @RequestBody CreatePostRequest req
    ) {
        Long userId = requireLogin(authorization);
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
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @PathVariable("id") Long id,
            @Valid @RequestBody UpdatePostRequest req
    ) {
        Long userId = requireLogin(authorization);
        PostDto dto = postService.update(userId, id, req);
        return ResponseEntity.ok(dto);
    }

    @DeleteMapping("/posts/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @PathVariable("id") Long id
    ) {
        Long userId = requireLogin(authorization);
        postService.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    private Long requireLogin(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌缺失");
        }
        String token = authorization.substring("Bearer ".length()).trim();
        Long userId = jwtTokenProvider.getUserIdFromToken(token);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "令牌无效或已过期");
        }
        return userId;
    }
}