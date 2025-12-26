package com.example.demo.threads.controller;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.threads.dto.CreateThreadRequest;
import com.example.demo.threads.dto.UpdateThreadRequest;
import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.service.ThreadService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/threads")
public class ThreadsController {
    private final ThreadService threadService;
    private final JwtTokenProvider jwtTokenProvider;

    public ThreadsController(ThreadService threadService, JwtTokenProvider jwtTokenProvider) {
        this.threadService = threadService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @PostMapping
    public ResponseEntity<ThreadDto> create(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @Valid @RequestBody CreateThreadRequest req
    ) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌缺失");
        }
        String token = authorization.substring("Bearer ".length()).trim();
        Long userId = jwtTokenProvider.getUserIdFromToken(token);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "令牌无效或已过期");
        }

        ThreadDto dto = threadService.create(userId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @GetMapping
    public ResponseEntity<java.util.Map<String, Object>> list(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "sectionId", required = false) Long sectionId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size
    ) {
        Page<ThreadDto> p = threadService.list(q, sectionId, page, size);
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("items", p.getContent());
        body.put("page", page);
        body.put("size", size);
        body.put("total", p.getTotalElements());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ThreadDto> getById(@PathVariable("id") Long id) {
        ThreadDto dto = threadService.getById(id);
        return ResponseEntity.ok(dto);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ThreadDto> update(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @PathVariable("id") Long id,
            @Valid @RequestBody UpdateThreadRequest req
    ) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌缺失");
        }
        String token = authorization.substring("Bearer ".length()).trim();
        Long userId = jwtTokenProvider.getUserIdFromToken(token);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "令牌无效或已过期");
        }
        ThreadDto dto = threadService.update(userId, id, req);
        return ResponseEntity.ok(dto);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @PathVariable("id") Long id
    ) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌缺失");
        }
        String token = authorization.substring("Bearer ".length()).trim();
        Long userId = jwtTokenProvider.getUserIdFromToken(token);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "令牌无效或已过期");
        }
        threadService.delete(userId, id);
        return ResponseEntity.noContent().build();
    }
}