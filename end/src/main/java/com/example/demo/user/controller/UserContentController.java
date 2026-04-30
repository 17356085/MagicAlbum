package com.example.demo.user.controller;

import com.example.demo.posts.dto.PostDto;
import com.example.demo.posts.service.PostService;
import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.service.ThreadService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
public class UserContentController extends AbstractUserControllerSupport {
    private final ThreadService threadService;
    private final PostService postService;

    public UserContentController(ThreadService threadService, PostService postService) {
        this.threadService = threadService;
        this.postService = postService;
    }

    @GetMapping("/me/threads")
    public ResponseEntity<Map<String, Object>> listMyThreads(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "sectionId", required = false) Long sectionId,
            @RequestParam(value = "sort", defaultValue = "updatedAt") String sort,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(toPageBody(threadService.listByAuthor(getUserId(), q, sectionId, sort, page, size), page, size));
    }

    @GetMapping("/{id}/threads")
    public ResponseEntity<Map<String, Object>> listThreadsByUser(
            @PathVariable("id") Long id,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "sectionId", required = false) Long sectionId,
            @RequestParam(value = "sort", defaultValue = "updatedAt") String sort,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(toPageBody(threadService.listByAuthor(id, q, sectionId, sort, page, size), page, size));
    }

    @GetMapping("/me/posts")
    public ResponseEntity<Map<String, Object>> listMyPosts(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "sectionId", required = false) Long sectionId,
            @RequestParam(value = "sort", defaultValue = "createdAt") String sort,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(toPageBody(postService.listByAuthor(getUserId(), q, sectionId, sort, page, size), page, size));
    }

    private Map<String, Object> toPageBody(Page<?> pageData, int page, int size) {
        Map<String, Object> body = new HashMap<>();
        body.put("items", pageData.getContent());
        body.put("page", page);
        body.put("size", size);
        body.put("total", pageData.getTotalElements());
        return body;
    }
}
