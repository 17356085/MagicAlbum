package com.example.demo.user.controller;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.posts.service.PostService;
import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.service.ThreadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = UserContentController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserContentControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ThreadService threadService;

    @MockitoBean
    PostService postService;

    @MockitoBean
    JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setAuth() {
        TestingAuthenticationToken auth = new TestingAuthenticationToken(42L, null);
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listMyThreads_should_report_effective_page_metadata_when_service_clamps_size() throws Exception {
        ThreadDto dto = new ThreadDto();
        dto.setId(17L);

        when(threadService.listByAuthor(eq(42L), eq(null), eq(null), eq("updatedAt"), eq(2), eq(20)))
                .thenReturn(new PageImpl<>(List.of(dto), PageRequest.of(1, 10), 21));

        mockMvc.perform(get("/api/v1/users/me/threads")
                        .param("page", "2")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.total").value(21))
                .andExpect(jsonPath("$.items[0].id").value(17));
    }

    @Test
    void listThreadsByUser_should_report_page_number_from_page_metadata() throws Exception {
        when(threadService.listByAuthor(eq(7L), eq(null), eq(null), eq("updatedAt"), eq(0), eq(20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        mockMvc.perform(get("/api/v1/users/7/threads")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
    }
}
