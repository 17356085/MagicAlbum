package com.example.demo.threads.controller;

import com.example.demo.threads.dto.CreateThreadRequest;
import com.example.demo.threads.dto.UpdateThreadRequest;
import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.service.ThreadService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/threads")
public class ThreadsController {
    private final ThreadService threadService;

    public ThreadsController(ThreadService threadService) {
        this.threadService = threadService;
    }

    @PostMapping
    public ResponseEntity<ThreadDto> create(
            @Valid @RequestBody CreateThreadRequest req
    ) {
        Long userId = getUserId();
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
            @PathVariable("id") Long id,
            @Valid @RequestBody UpdateThreadRequest req
    ) {
        Long userId = getUserId();
        ThreadDto dto = threadService.update(userId, id, req);
        return ResponseEntity.ok(dto);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable("id") Long id
    ) {
        Long userId = getUserId();
        threadService.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    private Long getUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Long) {
            return (Long) auth.getPrincipal();
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
    }
}
