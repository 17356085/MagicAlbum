package com.example.demo.threads.controller;

import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.service.ThreadService;
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

        when(threadService.list(eq(null), eq(null), eq(null), eq(1), eq(10)))
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
