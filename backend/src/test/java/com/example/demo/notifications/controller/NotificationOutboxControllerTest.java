package com.example.demo.notifications.controller;

import com.example.demo.admin.security.AdminAccessService;
import com.example.demo.common.GlobalExceptionHandler;
import com.example.demo.notifications.outbox.NotificationOutboxService;
import com.example.demo.notifications.outbox.NotificationOutboxStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationOutboxControllerTest {
    private AdminAccessService access;
    private NotificationOutboxService outbox;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        access = mock(AdminAccessService.class);
        outbox = mock(NotificationOutboxService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new NotificationOutboxController(outbox, access))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void counts_requires_authentication() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"))
                .when(access).requireAdmin(null);

        mockMvc.perform(get("/api/v1/admin/notification-outbox"))
                .andExpect(status().isUnauthorized());
        verify(access).requireAdmin(null);
    }

    @Test
    void diagnostics_requires_authentication() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"))
                .when(access).requireAdmin(null);

        mockMvc.perform(get("/api/v1/admin/notification-outbox/diagnostics"))
                .andExpect(status().isUnauthorized());
        verify(access).requireAdmin(null);
    }

    @Test
    void counts_rejects_non_admin() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(authentication(99L));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required"))
                .when(access).requireAdmin(99L);

        mockMvc.perform(get("/api/v1/admin/notification-outbox"))
                .andExpect(status().isForbidden());
        verify(access).requireAdmin(99L);
    }

    @Test
    void admin_can_read_counts_and_retry_failed_event() throws Exception {
        when(outbox.count(NotificationOutboxStatus.PENDING)).thenReturn(2L);
        when(outbox.count(NotificationOutboxStatus.FAILED)).thenReturn(1L);
        when(outbox.count(NotificationOutboxStatus.DELIVERED)).thenReturn(3L);
        when(outbox.count(NotificationOutboxStatus.IN_FLIGHT)).thenReturn(0L);
        when(outbox.count(NotificationOutboxStatus.SUPPRESSED)).thenReturn(0L);
        when(outbox.oldestPendingAgeSeconds()).thenReturn(42L);
        when(outbox.manualRetry(17L)).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(authentication(42L));

        mockMvc.perform(get("/api/v1/admin/notification-outbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.PENDING").value(2))
                .andExpect(jsonPath("$.FAILED").value(1))
                .andExpect(jsonPath("$.DELIVERED").value(3));

        mockMvc.perform(get("/api/v1/admin/notification-outbox/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.PENDING").value(2))
                .andExpect(jsonPath("$.oldestPendingAgeSeconds").value(42));

        mockMvc.perform(post("/api/v1/admin/notification-outbox/17/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queued").value(true));

        verify(access, org.mockito.Mockito.times(3)).requireAdmin(42L);
        verify(outbox).manualRetry(17L);
    }

    private TestingAuthenticationToken authentication(Long userId) {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(userId, null);
        authentication.setAuthenticated(true);
        return authentication;
    }
}
