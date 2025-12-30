package com.example.demo.ai.controller;

import com.example.demo.ai.service.AiService;
import com.example.demo.common.config.RabbitMQConfig;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final AiService aiService;
    private final ThreadRepository threadRepository;
    private final RabbitTemplate rabbitTemplate;

    public AiController(AiService aiService, ThreadRepository threadRepository, RabbitTemplate rabbitTemplate) {
        this.aiService = aiService;
        this.threadRepository = threadRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @PostMapping("/summary/{threadId}")
    public Map<String, Object> triggerSummary(@PathVariable Long threadId, 
                                              @RequestParam(defaultValue = "false") boolean force) {
        Thread thread = threadRepository.findById(threadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在"));

        if (!force && thread.getSummary() != null && !thread.getSummary().isBlank()) {
            return Map.of("status", "COMPLETED", "summary", thread.getSummary());
        }

        // 触发异步任务
        thread.setSummaryStatus("PENDING");
        threadRepository.save(thread);
        rabbitTemplate.convertAndSend(RabbitMQConfig.QUEUE_THREAD_SUMMARY, threadId);

        return Map.of("status", "PENDING", "message", "摘要生成任务已提交");
    }

    @GetMapping("/summary/{threadId}")
    public Map<String, Object> getSummary(@PathVariable Long threadId) {
        Thread thread = threadRepository.findById(threadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在"));
        
        return Map.of(
            "summary", thread.getSummary() == null ? "" : thread.getSummary(),
            "status", thread.getSummaryStatus() == null ? "PENDING" : thread.getSummaryStatus()
        );
    }

    /**
     * SSE 流式对话接口
     * 实际项目中建议使用 WebClient + Reactor 实现真正的流式，
     * 这里为简化演示，使用 Flux 包裹同步调用的结果（伪流式），或者分段输出。
     * 若要真实流式，需改造 AiService 使用 WebClient.
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, String>> chatStream(@RequestBody Map<String, Object> payload) {
        List<Map<String, String>> messages = (List<Map<String, String>>) payload.get("messages");
        
        // 简单模拟：因为 RestClient 是同步的，这里暂时一次性返回，或者按字符切分模拟打字机效果
        // 为了演示效果，我们先获取完整回复，然后切分
        return Flux.create(sink -> {
            try {
                String fullResponse = aiService.chatSync(messages);
                // 模拟打字机效果，每 50ms 发送几个字符
                int chunkSize = 2;
                for (int i = 0; i < fullResponse.length(); i += chunkSize) {
                    int end = Math.min(i + chunkSize, fullResponse.length());
                    String chunk = fullResponse.substring(i, end);
                    sink.next(Map.of("content", chunk));
                    try { java.lang.Thread.sleep(50); } catch (InterruptedException ignored) {}
                }
                sink.complete();
            } catch (Exception e) {
                sink.error(e);
            }
        });
    }
}
