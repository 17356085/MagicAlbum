package com.example.demo.ai.controller;

import com.example.demo.ai.dto.AiChatRequest;
import com.example.demo.ai.service.AiQuotaService;
import com.example.demo.ai.service.AiService;
import com.example.demo.ai.service.AiStreamHandle;
import com.example.demo.ai.service.AiSummaryCacheService;
import com.example.demo.common.RequestIdentity;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    private final AiService aiService;
    private final AiSummaryCacheService aiSummaryCacheService;
    private final ThreadRepository threadRepository;
    private final RabbitTemplate rabbitTemplate;
    private final boolean rabbitEnabled;
    private final AiQuotaService aiQuotaService;
    private final long streamTimeoutMs;
    private final int maxContextChars;

    public AiController(
            AiService aiService,
            AiSummaryCacheService aiSummaryCacheService,
            ThreadRepository threadRepository,
            ObjectProvider<RabbitTemplate> rabbitTemplateProvider,
            @Value("${app.rabbit.enabled:false}") boolean rabbitEnabled,
            AiQuotaService aiQuotaService,
            @Value("${app.ai.request-timeout-seconds:30}") long streamTimeoutSeconds,
            @Value("${app.ai.max-context-chars:12000}") int maxContextChars
    ) {
        this.aiService = aiService;
        this.aiSummaryCacheService = aiSummaryCacheService;
        this.threadRepository = threadRepository;
        this.rabbitTemplate = rabbitTemplateProvider == null ? null : rabbitTemplateProvider.getIfAvailable();
        this.rabbitEnabled = rabbitEnabled;
        this.aiQuotaService = aiQuotaService;
        this.streamTimeoutMs = Math.max(5_000L, Math.min(streamTimeoutSeconds, 120L) * 1_000L);
        this.maxContextChars = Math.max(1_000, Math.min(maxContextChars, 50_000));
    }

    @PostMapping("/summary/{threadId}")
    public Map<String, Object> triggerSummary(@PathVariable Long threadId,
                                              @RequestParam(defaultValue = "false") boolean force,
                                              @AuthenticationPrincipal Long userId,
                                              HttpServletRequest httpRequest) {
        aiQuotaService.enforceRequest(userId, RequestIdentity.clientIp(httpRequest));
        Thread thread = findNormalThread(threadId);

        if ("PROCESSING".equals(thread.getSummaryStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "摘要正在生成");
        }
        if (!force && thread.getSummary() != null && !thread.getSummary().isBlank()) {
            return Map.of("status", "COMPLETED", "summary", thread.getSummary());
        }

        if (!rabbitEnabled || rabbitTemplate == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "摘要生成服务暂不可用");
        }

        if (threadRepository.updateSummaryStatusIfNormal(threadId, "PENDING") == 0) {
            Thread current = threadRepository.findById(threadId).orElse(null);
            if (current != null && "NORMAL".equals(current.getStatus())
                    && "PROCESSING".equals(current.getSummaryStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "摘要正在生成");
            }
            aiSummaryCacheService.evict(threadId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在");
        }
        aiSummaryCacheService.put(threadId, thread.getSummary(), "PENDING");

        try {
            rabbitTemplate.convertAndSend(com.example.demo.common.config.RabbitMQConfig.QUEUE_THREAD_SUMMARY, threadId);
        } catch (RuntimeException ex) {
            // Keep the durable PENDING intent: a failed publisher must not restore over a newer request.
            aiSummaryCacheService.evict(threadId);
            log.warn("Failed to enqueue AI summary for thread {}", threadId, ex);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "摘要生成服务暂不可用");
        }
        return Map.of("status", "PENDING", "message", "摘要生成任务已提交");
    }

    @GetMapping("/summary/{threadId}")
    public Map<String, Object> getSummary(@PathVariable Long threadId) {
        Thread thread = findNormalThread(threadId);
        // The database owns lease state; a delayed cache fill must not roll a new task back to an old status.
        String summary = thread.getSummary() == null ? "" : thread.getSummary();
        String status = thread.getSummaryStatus() == null ? "PENDING" : thread.getSummaryStatus();
        aiSummaryCacheService.put(threadId, summary, status);
        return Map.of("summary", summary, "status", status);
    }

    private Thread findNormalThread(Long threadId) {
        Thread thread = threadRepository.findById(threadId).orElse(null);
        if (thread == null || thread.getStatus() == null || !"NORMAL".equals(thread.getStatus())) {
            aiSummaryCacheService.evict(threadId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在");
        }
        return thread;
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
