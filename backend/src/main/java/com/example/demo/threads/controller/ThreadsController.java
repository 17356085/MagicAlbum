package com.example.demo.threads.controller;

import com.example.demo.common.PageResponse;
import com.example.demo.threads.dto.CreateThreadRequest;
import com.example.demo.threads.dto.ThreadLikeResponse;
import com.example.demo.threads.dto.UpdateThreadRequest;
import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.service.ThreadService;
import com.example.demo.search.service.ThreadSearchService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/threads")
public class ThreadsController {
    private final ThreadService threadService;
    private final ThreadSearchService threadSearchService;

    public ThreadsController(ThreadService threadService, ThreadSearchService threadSearchService) {
        this.threadService = threadService;
        this.threadSearchService = threadSearchService;
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
    public ResponseEntity<PageResponse<ThreadDto>> list(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "tag", required = false) String tag,
            @RequestParam(value = "sectionId", required = false) Long sectionId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        Page<ThreadDto> p = threadSearchService.list(q, tag, sectionId, page, size);
        return ResponseEntity.ok(PageResponse.from(p));
    }

    @GetMapping("/following")
    public ResponseEntity<PageResponse<ThreadDto>> following(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size
    ) {
        Page<ThreadDto> result = threadService.listFollowing(getUserId(), page, size);
        return ResponseEntity.ok(PageResponse.from(result));
    }

    @GetMapping("/ranking")
    public ResponseEntity<PageResponse<ThreadDto>> ranking(
            @RequestParam(value = "sectionId", required = false) Long sectionId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        Page<ThreadDto> p = threadService.ranking(sectionId, page, size);
        return ResponseEntity.ok(PageResponse.from(p));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ThreadDto> getById(@PathVariable("id") Long id) {
        ThreadDto dto = threadService.getById(id);
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/{id}/like")
    public ResponseEntity<ThreadLikeResponse> like(@PathVariable("id") Long id) {
        Long userId = getUserId();
        return ResponseEntity.ok(threadService.like(userId, id));
    }

    @GetMapping("/{id}/like")
    public ResponseEntity<ThreadLikeResponse> likeStatus(@PathVariable("id") Long id) {
        Long userId = getUserId();
        return ResponseEntity.ok(threadService.likeStatus(userId, id));
    }

    @DeleteMapping("/{id}/like")
    public ResponseEntity<ThreadLikeResponse> unlike(@PathVariable("id") Long id) {
        Long userId = getUserId();
        return ResponseEntity.ok(threadService.unlike(userId, id));
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
