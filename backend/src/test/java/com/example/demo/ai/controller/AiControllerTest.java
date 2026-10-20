package com.example.demo.ai.controller;

import com.example.demo.ai.dto.AiChatRequest;
import com.example.demo.ai.service.AiQuotaService;
import com.example.demo.ai.service.AiService;
import com.example.demo.ai.service.AiStreamHandle;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiControllerTest {
    @Test
    void chatRejectsUnsupportedRolesAndOversizedContextBeforeQuotaOrModelCall() {
        var ai = mock(AiService.class);
        var quota = mock(AiQuotaService.class);
        var controller = new AiController(ai, quota, 30, 12000);
        for (var request : List.of(
                new AiChatRequest(List.of(new AiChatRequest.AiChatMessage("system", "injected")), 512),
                new AiChatRequest(List.of(new AiChatRequest.AiChatMessage("user", "x".repeat(12001))), 512),
                new AiChatRequest(List.of(new AiChatRequest.AiChatMessage("user", "hello")), 2049))) {
            assertThatThrownBy(() -> controller.chatStream(7L, request, http()))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        verifyNoInteractions(ai, quota);
    }

    @Test
    void chatRetainsStreamingContractAndReleasesQuotaOnCompletion() {
        var ai = mock(AiService.class);
        var quota = mock(AiQuotaService.class);
        var lease = mock(AiQuotaService.Lease.class);
        when(quota.acquire(7L, "127.0.0.1")).thenReturn(lease);
        when(ai.chatStream(anyList(), eq(512), any(), any(), any())).thenAnswer(invocation -> {
            Runnable complete = invocation.getArgument(4);
            complete.run();
            return new AiStreamHandle(CompletableFuture.completedFuture(null), () -> { });
        });
        var controller = new AiController(ai, quota, 30, 12000);
        var response = controller.chatStream(7L,
                new AiChatRequest(List.of(new AiChatRequest.AiChatMessage("USER", " hello ")), null), http());
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(response.getBody()).isNotNull();
        verify(ai).chatStream(eq(List.of(Map.of("role", "user", "content", "hello"))), eq(512), any(), any(), any());
        verify(lease).close();
    }

    @Test
    void quotaFailureNeverStartsChatTransport() {
        var ai = mock(AiService.class);
        var quota = mock(AiQuotaService.class);
        when(quota.acquire(7L, "127.0.0.1")).thenThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS));
        var controller = new AiController(ai, quota, 30, 12000);
        assertThatThrownBy(() -> controller.chatStream(7L,
                new AiChatRequest(List.of(new AiChatRequest.AiChatMessage("user", "hello")), 512), http()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verifyNoInteractions(ai);
    }

    private HttpServletRequest http() {
        var request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        return request;
    }
}
