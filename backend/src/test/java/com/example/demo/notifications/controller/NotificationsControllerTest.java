package com.example.demo.notifications.controller;

import com.example.demo.notifications.dto.NotificationDto;
import com.example.demo.notifications.dto.NotificationPageResponse;
import com.example.demo.notifications.service.NotificationService;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationsControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void list_uses_verified_principal_and_forwards_browser_authorization() throws Exception {
        NotificationService service = mock(NotificationService.class);
        when(service.list(42L, "Bearer browser-token", null, null, 1, 20))
                .thenReturn(new NotificationPageResponse(java.util.List.of(), 1, 20, 0, null));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new NotificationsController(service)).build();
        setAuthenticatedUser(42L);

        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer browser-token")
                        .header("X-User-Id", "999"))
                .andExpect(status().isOk());

        verify(service).list(eq(42L), eq("Bearer browser-token"), isNull(), isNull(), eq(1), eq(20));
    }

    @Test
    void mark_read_uses_verified_principal_and_forwards_browser_authorization() throws Exception {
        NotificationService service = mock(NotificationService.class);
        when(service.markRead(42L, "Bearer browser-token", 7L))
                .thenReturn(new NotificationDto(7L, "reply", "Title", "Content", true, Instant.parse("2026-10-14T10:00:00Z")));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new NotificationsController(service)).build();
        setAuthenticatedUser(42L);

        mockMvc.perform(patch("/api/v1/notifications/7/read")
                        .header("Authorization", "Bearer browser-token")
                        .header("X-User-Id", "999"))
                .andExpect(status().isOk());

        verify(service).markRead(eq(42L), eq("Bearer browser-token"), eq(7L));
    }

    private void setAuthenticatedUser(Long userId) {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(userId, null);
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
