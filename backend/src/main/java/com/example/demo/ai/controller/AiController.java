package com.example.demo.ai.controller;

import com.example.demo.ai.dto.AiChatRequest;
import com.example.demo.ai.service.AiQuotaService;
import com.example.demo.ai.service.AiService;
import com.example.demo.ai.service.AiStreamHandle;
import com.example.demo.common.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final AiService aiService;
    private final AiQuotaService aiQuotaService;
    private final long streamTimeoutMs;
    private final int maxContextChars;

    public AiController(
            AiService aiService,
            AiQuotaService aiQuotaService,
            @Value("${app.ai.request-timeout-seconds:30}") long streamTimeoutSeconds,
            @Value("${app.ai.max-context-chars:12000}") int maxContextChars
    ) {
        this.aiService = aiService;
        this.aiQuotaService = aiQuotaService;
        this.streamTimeoutMs = Math.max(5_000L, Math.min(streamTimeoutSeconds, 120L) * 1_000L);
        this.maxContextChars = Math.max(1_000, Math.min(maxContextChars, 50_000));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> chatStream(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody AiChatRequest request,
            HttpServletRequest httpRequest
    ) {
        validateRequest(request);
        int maxOutputTokens = request.maxOutputTokens() == null ? 512 : request.maxOutputTokens();
        if (maxOutputTokens < 1 || maxOutputTokens > 2_048) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "maxOutputTokens 必须在 1 到 2048 之间");
        }

        AiQuotaService.Lease lease = aiQuotaService.acquire(userId, RequestIdentity.clientIp(httpRequest));
        SseEmitter emitter = new SseEmitter(streamTimeoutMs);
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<AiStreamHandle> handleRef = new AtomicReference<>();

        emitter.onCompletion(() -> {
            lease.close();
            AiStreamHandle handle = handleRef.get();
            if (handle != null && finished.compareAndSet(false, true)) {
                handle.cancel();
            }
        });
        emitter.onTimeout(() -> {
            lease.close();
            AiStreamHandle handle = handleRef.get();
            if (handle != null) {
                handle.cancel();
            }
            emitter.complete();
        });

        List<Map<String, String>> messages = request.messages().stream()
                .map(message -> Map.of("role", message.role().trim().toLowerCase(),
                        "content", message.content().trim()))
                .toList();
        AiStreamHandle handle = aiService.chatStream(
                messages,
                maxOutputTokens,
                content -> {
                    if (finished.get()) {
                        return;
                    }
                    try {
                        emitter.send(SseEmitter.event().data(Map.of("content", content)));
                    } catch (IOException ex) {
                        if (finished.compareAndSet(false, true)) {
                            lease.close();
                            AiStreamHandle current = handleRef.get();
                            if (current != null) {
                                current.cancel();
                            }
                            emitter.complete();
                        }
                    }
                },
                error -> {
                    if (finished.compareAndSet(false, true)) {
                        lease.close();
                        try {
                            emitter.send(SseEmitter.event().name("error")
                                    .data(Map.of("message", "AI 服务暂不可用")));
                        } catch (IOException clientClosed) {
                            emitter.complete();
                        }
                        emitter.complete();
                    }
                },
                () -> {
                    if (finished.compareAndSet(false, true)) {
                        lease.close();
                        try {
                            emitter.send(SseEmitter.event().data("[DONE]"));
                        } catch (IOException clientClosed) {
                            // The stream can finish after the client has already cancelled.
                        } finally {
                            emitter.complete();
                        }
                    }
                }
        );
        handleRef.set(handle);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    private void validateRequest(AiChatRequest request) {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messages 不能为空");
        }
        int totalChars = 0;
        for (AiChatRequest.AiChatMessage message : request.messages()) {
            if (!"user".equalsIgnoreCase(message.role()) && !"assistant".equalsIgnoreCase(message.role())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息角色不支持");
            }
            totalChars += message.content().length();
        }
        if (totalChars > maxContextChars) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上下文内容过长");
        }
    }
}
