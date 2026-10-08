package com.example.demo.ai.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 提示词构建器
 * 负责根据不同的任务和模型构建合适的提示词
 * 使用 modelName 参数自适应调整提示词策略以适应不同的 AI 模型
 */
public class AiPromptBuilder {
    private final String modelName;
    private final String rolePrompt;

    // 模型特定的配置 - 根据模型调整摘要长度限制
    private static final Map<String, Integer> MODEL_MAX_LENGTH = Map.ofEntries(
            Map.entry("gpt-4", 150),
            Map.entry("gpt-3.5-turbo", 100),
            Map.entry("claude", 120),
            Map.entry("claude-3", 150),
            Map.entry("doubao", 250)
    );

    public AiPromptBuilder(String modelName) {
        this.modelName = modelName;
        this.rolePrompt = loadRolePrompt();
    }

    public List<Map<String, String>> buildLegacySummaryMessages(String content) {
        String safeContent = content == null ? "" : (content.length() > 3000 ? content.substring(0, 3000) : content);
        String system = rolePrompt + "\n\n当前任务：请为以下帖子内容生成一个 50-100 字的简明摘要。直接输出摘要内容，不要包含“摘要：”等前缀。";
        return List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", safeContent)
        );
    }

    public List<Map<String, String>> buildLegacyChatMessages(List<Map<String, String>> messages) {
        String system = rolePrompt + "\n\n当前任务：请用上述角色风格回答用户的问题。";
        Map<String, String> sysMsg = Map.of("role", "system", "content", system);

        if (messages == null || messages.isEmpty()) {
            return List.of(sysMsg);
        }
        ArrayList<Map<String, String>> merged = new ArrayList<>(messages.size() + 1);
        merged.add(sysMsg);
        for (Map<String, String> message : messages) {
            if (message == null || "system".equals(message.get("role"))) {
                continue;
            }
            merged.add(message);
        }
        return merged;
    }

    private String loadRolePrompt() {
        try (InputStream input = AiPromptBuilder.class.getResourceAsStream("/ai/sakiko-role-prompt.md")) {
            if (input == null) {
                return "";
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }

    /**
     * 构建生成摘要的提示词
     * 根据模型类型自动调整长度限制，确保与模型能力匹配
     */
    public String buildSummaryPrompt(String content) {
        int maxLength = getMaxLengthForModel();
        return String.format(
                "请为以下内容生成一个简洁的摘要，控制在 %d 字以内：\n\n%s",
                maxLength,
                content
        );
    }

    /**
     * 构建聊天提示词
     * 根据模型能力添加系统提示和上下文指导
     */
    public String buildChatPrompt(List<Map<String, String>> messages) {
        StringBuilder prompt = new StringBuilder();

        // 对于更强大的模型，添加系统提示以获得更好的结果
        if (isAdvancedModel()) {
            prompt.append("系统: 你是一个有帮助的助手。请用中文回复，并保持回复简洁有效。\n");
        }

        for (Map<String, String> message : messages) {
            String role = message.getOrDefault("role", "user");
            String content = message.getOrDefault("content", "");
            prompt.append(String.format("%s: %s\n", role, content));
        }
        return prompt.toString();
    }

    /**
     * 获取当前模型名称
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * 判断是否为高级模型（支持更复杂的指令）
     */
    private boolean isAdvancedModel() {
        return modelName != null && (
                modelName.contains("gpt-4") ||
                        modelName.contains("claude-3") ||
                        modelName.contains("claude")
        );
    }

    /**
     * 获取该模型的最大摘要长度限制
     */
    private int getMaxLengthForModel() {
        if (modelName == null) {
            return 100; // 默认值
        }

        // 精确匹配
        if (MODEL_MAX_LENGTH.containsKey(modelName)) {
            return MODEL_MAX_LENGTH.get(modelName);
        }

        // 模糊匹配（检查是否包含）
        for (Map.Entry<String, Integer> entry : MODEL_MAX_LENGTH.entrySet()) {
            if (modelName.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        // 默认返回
        return 100;
    }
}
