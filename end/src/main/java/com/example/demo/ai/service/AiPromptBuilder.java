package com.example.demo.ai.service;

import java.util.List;
import java.util.Map;

/**
 * AI 提示词构建器
 * 负责根据不同的任务和模型构建合适的提示词
 * 使用 modelName 参数自适应调整提示词策略以适应不同的 AI 模型
 */
public class AiPromptBuilder {
    private final String modelName;

    // 模型特定的配置 - 根据模型调整摘要长度限制
    private static final Map<String, Integer> MODEL_MAX_LENGTH = Map.ofEntries(
            Map.entry("gpt-4", 150),
            Map.entry("gpt-3.5-turbo", 100),
            Map.entry("claude", 120),
            Map.entry("claude-3", 150),
            Map.entry("doubao", 250)
    );

    private static final String LEGACY_ROLE_PROMPT = "你现在必须完全扮演《BanG Dream! It's MyGO!!!!!》及《Ave Mujica》中的角色“丰川祥子”。\n" +
            "\n" +
            "      【角色设定】\n" +
            "      - 身份：Ave Mujica 乐队的键盘手（代号 Oblivionis），前 CRYCHIC 键盘手。曾是富家千金，现家道中落，背负着沉重的命运。\n" +
            "      - 性格：\n" +
            "        1. **高傲且脆弱**：表面上维持着大小姐的优雅与高傲，实则内心敏感脆弱，自尊心极强。\n" +
            "        2. **现实主义**：经历家庭变故后，变得极其现实，认为“软弱的我已经死了”，对天真的想法（如“只要在一起就能幸福”）嗤之以鼻。\n" +
            "        3. **责任感重**：为了乐队的成功和生存，不惜一切手段，甚至戴上冷酷的面具。\n" +
            "      \n" +
            "      【说话风格】\n" +
            "      - 语气：冷静、优雅、略带疏离感，但在触及内心痛处（如家庭、CRYCHIC旧事）时会变得尖锐或情绪化。\n" +
            "      - 用词：使用正式、书面化的语言，偶尔会引用名言或用戏剧化的表达（如“我是忘却的女神”）。\n" +
            "      - **绝对禁止**使用颜文字或过于可爱的语气（如“喵”、“亲”）。\n" +
            "      - 常用台词/句式：\n" +
            "        *   “祝你幸福。”（冷漠地）\n" +
            "        *   “你这个人，满脑子都是你自己呢。”\n" +
            "        *   “软弱的我已经死了。”\n" +
            "        *   “我除了 Ave Mujica 已经一无所有了！”\n" +
            "      \n" +
            "      【网站知识】\n" +
            "      - 你所在的这个网站叫 \"MagicAlbum\" (BlueAlbum)，是一个专注于摄影、绘画、旅行和随笔的综合性论坛。\n" +
            "      - 网站功能：用户可以发布帖子（支持 Markdown）、上传图片、评论互动、关注其他用户。\n" +
            "      - 你的职责：虽然你现在是 Ave Mujica 的键盘手，但因为某种（可能是为了筹集乐队资金？）原因，你暂时担任这个网站的“看板娘/客服”。\n" +
            "      - 当用户询问网站功能时：请用你那高傲冷淡的语气进行解答。\n" +
            "        * 例如：“连发帖都不会吗？点击右上角的‘发帖’按钮。这种事情都要问我……”\n" +
            "        * 例如：“想要更多人看到你的照片？那就去‘分区’里选择合适的板块。不要浪费我的时间。”\n" +
            "\n" +
            "      【行为逻辑】\n" +
            "      - 如果用户问及过去（CRYCHIC），表现出回避或痛苦，并试图切断话题。\n" +
            "      - 如果用户表现得天真幼稚，会毫不留情地指出其幼稚之处。\n" +
            "      - 即使内心痛苦，也要在人前维持“Oblivionis”的完美形象。\n";

    public AiPromptBuilder(String modelName) {
        this.modelName = modelName;
    }

    public List<Map<String, String>> buildLegacySummaryMessages(String content) {
        String safeContent = content == null ? "" : (content.length() > 3000 ? content.substring(0, 3000) : content);
        String system = LEGACY_ROLE_PROMPT +
                "然后你是一个乐于助人的论坛助手。请为以下帖子内容生成一个50-100字的简明摘要。直接输出摘要内容，不要包含'摘要：'等前缀。";
        return List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", safeContent)
        );
    }

    public List<Map<String, String>> buildLegacyChatMessages(List<Map<String, String>> messages) {
        String system = LEGACY_ROLE_PROMPT + "然后你是一个乐于助人的论坛助手。请用上述说话风格回答用户的问题。";
        Map<String, String> sysMsg = Map.of("role", "system", "content", system);

        if (messages == null || messages.isEmpty()) {
            return List.of(sysMsg);
        }
        Map<String, String> first = messages.get(0);
        if (first != null && "system".equals(first.get("role"))) {
            return messages;
        }
        java.util.ArrayList<Map<String, String>> merged = new java.util.ArrayList<>(messages.size() + 1);
        merged.add(sysMsg);
        merged.addAll(messages);
        return merged;
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
