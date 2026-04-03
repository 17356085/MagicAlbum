package com.example.demo.ai.service;

import java.util.List;
import java.util.Map;

/**
 * Mock AI 提供者
 * 职责：在未配置真实 API Key 时，提供模拟的 AI 响应
 */
public class MockAiProvider implements AiProvider {

    @Override
    public String generateSummary(String content) {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {
        }
        return "【测试模式】这是一段自动生成的模拟摘要。在未配置真实 API Key 时，系统会返回此占位文本，以验证流程是否通畅。真实内容应包含对帖子核心观点的概括。";
    }

    @Override
    public String chat(List<Map<String, String>> messages) {
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
        }

        String lastUserMsg = "未知";
        if (messages != null && !messages.isEmpty()) {
            Map<String, String> last = messages.get(messages.size() - 1);
            if ("user".equals(last.get("role"))) {
                lastUserMsg = last.get("content");
            }
        }
        return "【Mock回复】我收到了你说：" + lastUserMsg + "。目前处于测试模式，没有真实调用 AI，但整个链路是通的哦！(｡･ω･｡)";
    }
}

