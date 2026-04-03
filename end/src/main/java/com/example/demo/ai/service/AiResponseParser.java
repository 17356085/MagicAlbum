package com.example.demo.ai.service;

import java.util.List;
import java.util.Map;

/**
 * AI 响应解析器
 * 职责：统一解析 AI API 的响应格式
 */
public class AiResponseParser {

    /**
     * 解析聊天完成响应，提取内容
     * @return 响应内容，如果解析失败则返回 null
     */
    public String parseResponse(Map response) {
        if (response == null || !response.containsKey("choices")) {
            return null;
        }

        List choices = (List) response.get("choices");
        if (choices == null || choices.isEmpty()) {
            return null;
        }

        Map firstChoice = (Map) choices.get(0);
        Map message = (Map) firstChoice.get("message");

        if (message != null && message.containsKey("content")) {
            return (String) message.get("content");
        }

        return null;
    }
}

