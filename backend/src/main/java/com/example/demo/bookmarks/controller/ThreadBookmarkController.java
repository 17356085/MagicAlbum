package com.example.demo.bookmarks.controller;

import com.example.demo.bookmarks.dto.BookmarkPageResponse;
import com.example.demo.bookmarks.dto.BookmarkStatusResponse;
import com.example.demo.bookmarks.service.ThreadBookmarkService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class ThreadBookmarkController {

    private final ThreadBookmarkService bookmarkService;

    public ThreadBookmarkController(ThreadBookmarkService bookmarkService) {
        this.bookmarkService = bookmarkService;
    }

    @GetMapping("/threads/{id}/bookmark")
    public BookmarkStatusResponse getStatus(@PathVariable("id") Long threadId) {
        return bookmarkService.getStatus(currentUserId(), threadId);
    }

    @PostMapping("/threads/{id}/bookmark")
    public BookmarkStatusResponse add(@PathVariable("id") Long threadId) {
        return bookmarkService.add(currentUserId(), threadId);
    }

    @DeleteMapping("/threads/{id}/bookmark")
    public BookmarkStatusResponse remove(@PathVariable("id") Long threadId) {
        return bookmarkService.remove(currentUserId(), threadId);
    }

    @GetMapping("/users/me/bookmarks")
    public BookmarkPageResponse list(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size
    ) {
        return bookmarkService.list(currentUserId(), page, size);
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Long userId) {
            return userId;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
    }
}
