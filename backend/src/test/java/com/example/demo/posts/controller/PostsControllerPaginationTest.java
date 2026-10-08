package com.example.demo.posts.controller;

import com.example.demo.common.DistributedRateLimiter;
import com.example.demo.posts.dto.PostDto;
import com.example.demo.posts.service.PostService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostsControllerPaginationTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listByThread_reports_page_metadata_after_service_clamps_request() {
        SecurityContextHolder.clearContext();
        PostService postService = mock(PostService.class);
        DistributedRateLimiter rateLimiter = mock(DistributedRateLimiter.class);
        when(postService.listByThread(eq(7L), nullable(Long.class), eq("time"), eq(0), eq(100)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        var body = new PostsController(postService, rateLimiter)
                .listByThread(7L, "time", 0, 100)
                .getBody();

        assertThat(body).containsEntry("page", 1).containsEntry("size", 50);
        verify(postService).listByThread(eq(7L), nullable(Long.class), eq("time"), eq(0), eq(100));
    }
}
