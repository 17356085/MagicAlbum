package com.example.demo.threads.controller;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.search.service.ThreadSearchService;
import com.example.demo.threads.dto.SectionThreadPreviewDto;
import com.example.demo.threads.dto.ThreadPreviewDto;
import com.example.demo.threads.service.ThreadService;
import com.example.demo.threads.service.ThreadSectionPreviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {ThreadSectionPreviewController.class, ThreadsController.class})
@AutoConfigureMockMvc(addFilters = false)
class ThreadSectionPreviewControllerWebMvcTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ThreadSectionPreviewService service;

    @MockitoBean
    private ThreadService legacyThreadService;

    @MockitoBean
    private ThreadSearchService legacySearchService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void literalSectionPreviewRouteReturnsBatchItemsInsteadOfThreadIdRoute() throws Exception {
        ThreadPreviewDto item = new ThreadPreviewDto(
                "9007199254740993", "Newest", "9007199254740995",
                "author", null, Instant.parse("2026-02-01T00:00:00Z"), "/cover.png");
        when(service.read(List.of(7L, 8L))).thenReturn(List.of(
                new SectionThreadPreviewDto("7", 1L, List.of(item)),
                new SectionThreadPreviewDto("8", 0L, List.of())));

        mockMvc.perform(get("/api/v1/threads/section-previews")
                        .param("sectionIds", "7,8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sectionId").value("7"))
                .andExpect(jsonPath("$[0].total").value(1))
                .andExpect(jsonPath("$[0].items[0].id").value("9007199254740993"))
                .andExpect(jsonPath("$[0].items[0].createdAt")
                        .value("2026-02-01T00:00:00Z"))
                .andExpect(jsonPath("$[0].items[0].authorNickname").hasJsonPath())
                .andExpect(jsonPath("$[0].items[0].authorNickname")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[1].items").isEmpty());

        verify(service).read(eq(List.of(7L, 8L)));
        verifyNoInteractions(legacyThreadService, legacySearchService);
    }

    @Test
    void nonNumericSectionIdIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/threads/section-previews")
                        .param("sectionIds", "not-a-number"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void serviceValidationIsMappedToBadRequest() throws Exception {
        doThrow(new ResponseStatusException(BAD_REQUEST, "sectionIds[0] 必须是正整数"))
                .when(service).read(List.of(0L));

        mockMvc.perform(get("/api/v1/threads/section-previews")
                        .param("sectionIds", "0"))
                .andExpect(status().isBadRequest());
    }
}
