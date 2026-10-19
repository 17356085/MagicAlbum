package com.example.demo.moderation.controller;

import com.example.demo.admin.security.AdminAccessService;
import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.moderation.dto.CreateReportRequest;
import com.example.demo.moderation.dto.ReportResolutionResponse;
import com.example.demo.moderation.dto.ReportResponse;
import com.example.demo.moderation.dto.ResolveReportRequest;
import com.example.demo.moderation.model.ReportAction;
import com.example.demo.moderation.model.ReportReason;
import com.example.demo.moderation.model.ReportStatus;
import com.example.demo.moderation.model.ReportTargetType;
import com.example.demo.moderation.service.ContentReportService;
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
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ContentReportController.class)
@AutoConfigureMockMvc(addFilters = false)
class ContentReportControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContentReportService reportService;

    @MockitoBean
    private AdminAccessService adminAccessService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void clearAuthBeforeEach() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearAuthAfterEach() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void admin_list_requires_authentication() throws Exception {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Authentication required"))
                .when(adminAccessService).requireAdmin((Long) null);

        mockMvc.perform(get("/api/v1/admin/reports"))
                .andExpect(status().isUnauthorized());

        verify(adminAccessService).requireAdmin(null);
    }

    @Test
    void admin_list_rejects_non_admin() throws Exception {
        authenticateAs(7L);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "无管理员权限"))
                .when(adminAccessService).requireAdmin(7L);

        mockMvc.perform(get("/api/v1/admin/reports"))
                .andExpect(status().isForbidden());

        verify(adminAccessService).requireAdmin(7L);
    }

    @Test
    void admin_list_returns_actual_page_metadata_and_base_report_fields() throws Exception {
        authenticateAs(42L);
        ReportResponse response = new ReportResponse(
                31L,
                7L,
                ReportTargetType.POST,
                21L,
                ReportReason.SPAM,
                "spam detail",
                ReportStatus.PENDING,
                Instant.parse("2026-10-07T00:00:00Z"),
                11L
        );
        when(reportService.list(eq(ReportStatus.PENDING), eq(2), eq(20)))
                .thenReturn(new PageImpl<>(List.of(response), PageRequest.of(1, 20), 41));

        mockMvc.perform(get("/api/v1/admin/reports")
                        .param("status", "PENDING")
                        .param("page", "2")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(41))
                .andExpect(jsonPath("$.items[0].id").value(31))
                .andExpect(jsonPath("$.items[0].targetType").value("POST"))
                .andExpect(jsonPath("$.items[0].targetId").value(21))
                .andExpect(jsonPath("$.items[0].targetThreadId").value(11))
                .andExpect(jsonPath("$.items[0].reason").value("SPAM"))
                .andExpect(jsonPath("$.items[0].detail").value("spam detail"))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.items[0].reporterId").value(7))
                .andExpect(jsonPath("$.items[0].createdAt").exists())
                .andExpect(jsonPath("$.items[0].reviewerId").doesNotExist())
                .andExpect(jsonPath("$.items[0].resolvedAt").doesNotExist());
        verify(adminAccessService).requireAdmin(42L);
    }

    @Test
    void submission_returns_base_report_metadata() throws Exception {
        authenticateAs(7L);
        when(reportService.create(eq(7L), any(CreateReportRequest.class))).thenReturn(
                new ReportResponse(
                        31L,
                        7L,
                        ReportTargetType.POST,
                        21L,
                        ReportReason.HARASSMENT,
                        "please review",
                        ReportStatus.PENDING,
                        Instant.parse("2026-10-07T00:00:00Z"),
                        11L
                )
        );

        mockMvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetType":"POST","targetId":21,"reason":"HARASSMENT","detail":"please review"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(31))
                .andExpect(jsonPath("$.targetType").value("POST"))
                .andExpect(jsonPath("$.targetId").value(21))
                .andExpect(jsonPath("$.targetThreadId").value(11))
                .andExpect(jsonPath("$.reason").value("HARASSMENT"))
                .andExpect(jsonPath("$.detail").value("please review"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.reporterId").value(7))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.reviewerId").doesNotExist())
                .andExpect(jsonPath("$.resolvedAt").doesNotExist());
    }

    @Test
    void resolution_returns_base_metadata_plus_reviewer_and_resolved_time() throws Exception {
        authenticateAs(42L);
        when(reportService.resolve(eq(42L), eq(31L), any(ResolveReportRequest.class))).thenReturn(
                new ReportResolutionResponse(
                        31L,
                        7L,
                        ReportTargetType.THREAD,
                        11L,
                        ReportReason.SPAM,
                        "spam detail",
                        ReportStatus.HIDDEN,
                        Instant.parse("2026-10-07T00:00:00Z"),
                        42L,
                        Instant.parse("2026-10-07T01:00:00Z")
                )
        );

        mockMvc.perform(post("/api/v1/admin/reports/31/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"HIDE\",\"reason\":\"审核说明\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(31))
                .andExpect(jsonPath("$.targetType").value("THREAD"))
                .andExpect(jsonPath("$.targetId").value(11))
                .andExpect(jsonPath("$.reason").value("SPAM"))
                .andExpect(jsonPath("$.detail").value("spam detail"))
                .andExpect(jsonPath("$.status").value("HIDDEN"))
                .andExpect(jsonPath("$.reporterId").value(7))
                .andExpect(jsonPath("$.targetThreadId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.reviewerId").value(42))
                .andExpect(jsonPath("$.resolvedAt").exists())
                .andExpect(jsonPath("$.createdAt").exists());
        verify(adminAccessService).requireAdmin(42L);
    }

    private void authenticateAs(Long userId) {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(userId, null);
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
