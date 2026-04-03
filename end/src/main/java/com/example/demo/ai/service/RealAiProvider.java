package com.example.demo.ai.service;

import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 真实 AI API 提供者
 * 负责调用实际的 AI 服务（OpenAI、Anthropic 等）
 */
public class RealAiProvider implements AiProvider {
    private final RestClient restClient;
    private final AiPromptBuilder promptBuilder;
    private final AiResponseParser responseParser;

    public RealAiProvider(RestClient restClient, AiPromptBuilder promptBuilder, AiResponseParser responseParser) {
        this.restClient = restClient;
        this.promptBuilder = promptBuilder;
        this.responseParser = responseParser;
    }

    @Override
    public String generateSummary(String content) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", promptBuilder.getModelName(),
                    "messages", promptBuilder.buildLegacySummaryMessages(content),
                    "temperature", 0.3
            );
            Map resp = restClient.post()
                    .uri("/chat/completions")
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);
            String parsed = responseParser.parseResponse(resp);
            return parsed == null ? "无法生成摘要" : parsed;
        } catch (Exception e) {
            return "摘要生成失败: " + (e.getMessage() == null ? "" : e.getMessage());
        }
    }

    @Override
    public String chat(List<Map<String, String>> messages) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", promptBuilder.getModelName(),
                    "messages", promptBuilder.buildLegacyChatMessages(messages),
                    "temperature", 0.7
            );
            Map resp = restClient.post()
                    .uri("/chat/completions")
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);
            String parsed = responseParser.parseResponse(resp);
            return parsed == null ? "连接断开" : parsed;
        } catch (Exception e) {
            return "连接断开";
        }
    }
}
