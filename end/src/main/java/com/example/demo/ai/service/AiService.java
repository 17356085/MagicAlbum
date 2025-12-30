package com.example.demo.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final RestClient restClient;

    @Value("${ai.model}")
    private String modelName;
    
    private final String apiKey;

    public AiService(@Value("${ai.base-url}") String baseUrl,
                     @Value("${ai.api-key:fake-key-for-testing}") String apiKey) {
        this.apiKey = apiKey;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    private boolean isMockMode() {
        return apiKey == null || apiKey.isBlank() || apiKey.contains("fake") || apiKey.contains("mock");
    }

    /**
     * 生成帖子摘要（同步调用）
     */
    public String generateSummary(String content) {
        if (isMockMode()) {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            return "【测试模式】这是一段自动生成的模拟摘要。在未配置真实 API Key 时，系统会返回此占位文本，以验证流程是否通畅。真实内容应包含对帖子核心观点的概括。";
        }
        try {
            // 简单截断防止超长
            String safeContent = content.length() > 3000 ? content.substring(0, 3000) : content;
            
            Map<String, Object> requestBody = Map.of(
                    "model", modelName,
                    "messages", List.of(
                            Map.of("role", "system", "content", "你是一个乐于助人的论坛助手。请为以下帖子内容生成一个50-100字的简明摘要。直接输出摘要内容，不要包含'摘要：'等前缀。"),
                            Map.of("role", "user", "content", safeContent)
                    ),
                    "temperature", 0.3
            );

            Map response = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);

            if (response != null && response.containsKey("choices")) {
                List choices = (List) response.get("choices");
                if (!choices.isEmpty()) {
                    Map firstChoice = (Map) choices.get(0);
                    Map message = (Map) firstChoice.get("message");
                    return (String) message.get("content");
                }
            }
            return "无法生成摘要";
        } catch (Exception e) {
            log.error("Failed to generate summary", e);
            return "摘要生成失败: " + e.getMessage();
        }
    }

    /**
     * 简单的流式对话模拟（暂用同步接口模拟，真实 SSE 需要 WebClient 或专用 SDK）
     * 为简化演示，这里返回 Flux<String> 但实际底层可能先全量获取。
     * 生产环境建议引入 spring-boot-starter-webflux 使用 WebClient 进行真正的流式处理。
     */
    public String chatSync(List<Map<String, String>> messages) {
        if (isMockMode()) {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            String lastUserMsg = "未知";
            if (messages != null && !messages.isEmpty()) {
                Map<String, String> last = messages.get(messages.size() - 1);
                if ("user".equals(last.get("role"))) {
                    lastUserMsg = last.get("content");
                }
            }
            return "【Mock回复】我收到了你说：" + lastUserMsg + "。目前处于测试模式，没有真实调用 AI，但整个链路是通的哦！(｡･ω･｡)";
        }
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", modelName,
                    "messages", messages,
                    "temperature", 0.7
            );

            Map response = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);

            if (response != null && response.containsKey("choices")) {
                List choices = (List) response.get("choices");
                if (!choices.isEmpty()) {
                    Map firstChoice = (Map) choices.get(0);
                    Map message = (Map) firstChoice.get("message");
                    return (String) message.get("content");
                }
            }
            return "小祥正在休息...";
        } catch (Exception e) {
            log.error("Chat failed", e);
            return "连接断开";
        }
    }
}
