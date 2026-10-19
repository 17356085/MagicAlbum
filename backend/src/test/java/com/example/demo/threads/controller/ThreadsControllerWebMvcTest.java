package com.example.demo.threads.controller;

import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.service.ThreadService;
import com.example.demo.search.service.ThreadSearchService;
import com.example.demo.auth.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ThreadsController.class)
@AutoConfigureMockMvc(addFilters = false)
class ThreadsControllerWebMvcTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ThreadService threadService;

    @MockitoBean
    ThreadSearchService threadSearchService;

    @MockitoBean
    JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setAuth() {
        TestingAuthenticationToken auth = new TestingAuthenticationToken(1L, null);
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void list_should_return_items_page_size_total() throws Exception {
        ThreadDto dto = new ThreadDto();
        dto.setId(10L);
        dto.setTitle("T1");
        dto.setCreatedAt(Instant.parse("2025-01-01T00:00:01Z"));

        when(threadSearchService.list(eq(null), eq(null), eq(null), eq(1), eq(10)))
                .thenReturn(new PageImpl<>(List.of(dto), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/v1/threads").param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(10))
                .andExpect(jsonPath("$.items[0].title").value("T1"));
    }

    @Test
    void list_should_report_effective_page_metadata_when_service_clamps_size() throws Exception {
        when(threadSearchService.list(eq(null), eq(null), eq(null), eq(2), eq(20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 10), 11));

        mockMvc.perform(get("/api/v1/threads").param("page", "2").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10));
    }

    @Test
    void ranking_should_report_effective_page_metadata_for_non_positive_request() throws Exception {
        when(threadService.ranking(eq(null), eq(0), eq(0)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));

        mockMvc.perform(get("/api/v1/threads/ranking").param("page", "0").param("size", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1));
    }

    @Test
    void following_should_return_effective_page_and_total() throws Exception {
        ThreadDto dto = new ThreadDto();
        dto.setId(51L);
        dto.setTitle("Followed author post");
        when(threadService.listFollowing(eq(1L), eq(2), eq(99)))
                .thenReturn(new PageImpl<>(List.of(dto), PageRequest.of(1, 20), 51));

        mockMvc.perform(get("/api/v1/threads/following").param("page", "2").param("size", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(51))
                .andExpect(jsonPath("$.items[0].id").value(51));
    }

    @Test
    void following_should_require_authenticated_user() throws Exception {
        SecurityContextHolder.clearContext();
        mockMvc.perform(get("/api/v1/threads/following"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_should_delegate_to_service_and_return_201() throws Exception {
        ThreadDto dto = new ThreadDto();
        dto.setId(123L);
        dto.setTitle("Hello");

        when(threadService.create(eq(1L), any())).thenReturn(dto);

        mockMvc.perform(post("/api/v1/threads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sectionId":1,"title":"Hello","content":"World"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(123))
                .andExpect(jsonPath("$.title").value("Hello"));
    }
}
