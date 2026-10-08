package com.example.demo.ai.service;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * AI 提供者接口
 * 职责：定义 AI 调用的统一规范，支持多种实现（真实 API、Mock等）
 */
public interface AiProvider {

    /**
     * 生成摘要
     */
    String generateSummary(String content);

    /**
     * Stream chat output from the provider. Implementations must not expose
     * provider errors or credentials to the caller.
     */
    AiStreamHandle chatStream(
            List<Map<String, String>> messages,
            int maxOutputTokens,
            Consumer<String> onContent,
            Consumer<Throwable> onError,
            Runnable onComplete
    );
}
