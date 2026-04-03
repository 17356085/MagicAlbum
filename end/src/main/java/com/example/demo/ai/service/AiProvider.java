package com.example.demo.ai.service;

import java.util.List;
import java.util.Map;

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
     * 进行聊天对话
     */
    String chat(List<Map<String, String>> messages);
}

