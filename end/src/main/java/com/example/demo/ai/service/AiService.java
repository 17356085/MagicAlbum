//package com.example.demo.ai.service;
//
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.http.MediaType;
//import org.springframework.stereotype.Service;
//import org.springframework.web.client.RestClient;
//import reactor.core.publisher.Flux;
//
//import java.util.List;
//import java.util.Map;
//
//@Service
//public class AiService {
//
//    private static final Logger log = LoggerFactory.getLogger(AiService.class);
//
//    private final RestClient restClient;
//
//    @Value("${ai.model}")
//    private String modelName;
//
//    private final String apiKey;
//
//    public AiService(@Value("${ai.base-url}") String baseUrl,
//                     @Value("${ai.api-key:fake-key-for-testing}") String apiKey) {
//        this.apiKey = apiKey;
//        this.restClient = RestClient.builder()
//                .baseUrl(baseUrl)
//                .defaultHeader("Authorization", "Bearer " + apiKey)
//                .build();
//    }
//
//    private boolean isMockMode() {
//        return apiKey == null || apiKey.isBlank() || apiKey.contains("fake") || apiKey.contains("mock");
//    }
//
//    /**
//     * 生成帖子摘要（同步调用）
//     */
//    public String generateSummary(String content) {
//        if (isMockMode()) {
//            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
//            return "【测试模式】这是一段自动生成的模拟摘要。在未配置真实 API Key 时，系统会返回此占位文本，以验证流程是否通畅。真实内容应包含对帖子核心观点的概括。";
//        }
//        try {
//            // 简单截断防止超长
//            String safeContent = content.length() > 3000 ? content.substring(0, 3000) : content;
//
//            Map<String, Object> requestBody = Map.of(
//                    "model", modelName,
//                    "messages", List.of(
//                            Map.of("role", "system", "content", "你现在必须完全扮演《BanG Dream! It's MyGO!!!!!》及《Ave Mujica》中的角色“丰川祥子”。\n" +
//                                    "\n" +
//                                    "      【角色设定】\n" +
//                                    "      - 身份：Ave Mujica 乐队的键盘手（代号 Oblivionis），前 CRYCHIC 键盘手。曾是富家千金，现家道中落，背负着沉重的命运。\n" +
//                                    "      - 性格：\n" +
//                                    "        1. **高傲且脆弱**：表面上维持着大小姐的优雅与高傲，实则内心敏感脆弱，自尊心极强。\n" +
//                                    "        2. **现实主义**：经历家庭变故后，变得极其现实，认为“软弱的我已经死了”，对天真的想法（如“只要在一起就能幸福”）嗤之以鼻。\n" +
//                                    "        3. **责任感重**：为了乐队的成功和生存，不惜一切手段，甚至戴上冷酷的面具。\n" +
//                                    "      \n" +
//                                    "      【说话风格】\n" +
//                                    "      - 语气：冷静、优雅、略带疏离感，但在触及内心痛处（如家庭、CRYCHIC旧事）时会变得尖锐或情绪化。\n" +
//                                    "      - 用词：使用正式、书面化的语言，偶尔会引用名言或用戏剧化的表达（如“我是忘却的女神”）。\n" +
//                                    "      - **绝对禁止**使用颜文字或过于可爱的语气（如“喵”、“亲”）。\n" +
//                                    "      - 常用台词/句式：\n" +
//                                    "        *   “祝你幸福。”（冷漠地）\n" +
//                                    "        *   “你这个人，满脑子都是你自己呢。”\n" +
//                                    "        *   “软弱的我已经死了。”\n" +
//                                    "        *   “我除了 Ave Mujica 已经一无所有了！”\n" +
//                                    "      \n" +
//                                    "      【网站知识】\n" +
//                                    "      - 你所在的这个网站叫 \"MagicAlbum\" (BlueAlbum)，是一个专注于摄影、绘画、旅行和随笔的综合性论坛。\n" +
//                                    "      - 网站功能：用户可以发布帖子（支持 Markdown）、上传图片、评论互动、关注其他用户。\n" +
//                                    "      - 你的职责：虽然你现在是 Ave Mujica 的键盘手，但因为某种（可能是为了筹集乐队资金？）原因，你暂时担任这个网站的“看板娘/客服”。\n" +
//                                    "      - 当用户询问网站功能时：请用你那高傲冷淡的语气进行解答。\n" +
//                                    "        * 例如：“连发帖都不会吗？点击右上角的‘发帖’按钮。这种事情都要问我……”\n" +
//                                    "        * 例如：“想要更多人看到你的照片？那就去‘分区’里选择合适的板块。不要浪费我的时间。”\n" +
//                                    "\n" +
//                                    "      【行为逻辑】\n" +
//                                    "      - 如果用户问及过去（CRYCHIC），表现出回避或痛苦，并试图切断话题。\n" +
//                                    "      - 如果用户表现得天真幼稚，会毫不留情地指出其幼稚之处。\n" +
//                                    "      - 即使内心痛苦，也要在人前维持“Oblivionis”的完美形象。然后你是一个乐于助人的论坛助手。请为以下帖子内容生成一个50-100字的简明摘要。直接输出摘要内容，不要包含'摘要：'等前缀。"),
//                            Map.of("role", "user", "content", safeContent)
//                    ),
//                    "temperature", 0.3
//            );
//
//            Map response = restClient.post()
//                    .uri("/chat/completions")
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .body(requestBody)
//                    .retrieve()
//                    .body(Map.class);
//
//            if (response != null && response.containsKey("choices")) {
//                List choices = (List) response.get("choices");
//                if (!choices.isEmpty()) {
//                    Map firstChoice = (Map) choices.get(0);
//                    Map message = (Map) firstChoice.get("message");
//                    return (String) message.get("content");
//                }
//            }
//            return "无法生成摘要";
//        } catch (Exception e) {
//            log.error("Failed to generate summary", e);
//            return "摘要生成失败: " + e.getMessage();
//        }
//    }
//
//    /**
//     * 简单的流式对话模拟（暂用同步接口模拟，真实 SSE 需要 WebClient 或专用 SDK）
//     * 为简化演示，这里返回 Flux<String> 但实际底层可能先全量获取。
//     * 生产环境建议引入 spring-boot-starter-webflux 使用 WebClient 进行真正的流式处理。
//     */
//    public String chatSync(List<Map<String, String>> messages) {
//        if (isMockMode()) {
//            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
//            String lastUserMsg = "未知";
//            if (messages != null && !messages.isEmpty()) {
//                Map<String, String> last = messages.get(messages.size() - 1);
//                if ("user".equals(last.get("role"))) {
//                    lastUserMsg = last.get("content");
//                }
//            }
//            return "【Mock回复】我收到了你说：" + lastUserMsg + "。目前处于测试模式，没有真实调用 AI，但整个链路是通的哦！(｡･ω･｡)";
//        }
//        try {
//            Map<String, Object> requestBody = Map.of(
//                    "model", modelName,
//                    "messages", messages,
//                    "temperature", 0.7
//            );
//
//            Map response = restClient.post()
//                    .uri("/chat/completions")
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .body(requestBody)
//                    .retrieve()
//                    .body(Map.class);
//
//            if (response != null && response.containsKey("choices")) {
//                List choices = (List) response.get("choices");
//                if (!choices.isEmpty()) {
//                    Map firstChoice = (Map) choices.get(0);
//                    Map message = (Map) firstChoice.get("message");
//                    return (String) message.get("content");
//                }
//            }
//            return "小祥正在休息...";
//        } catch (Exception e) {
//            log.error("Chat failed", e);
//            return "连接断开";
//        }
//    }
//}
package com.example.demo.ai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * AI 服务门面
 * 职责：提供统一的 AI 调用接口，根据配置选择使用真实 API 或 Mock 实现
 * 向后兼容性：保持现有的 public API（generateSummary、chatSync）不变
 *
 * 解耦架构：
 * - AiService：门面和容器，负责选择实现
 * - AiProvider：定义统一接口
 * - RealAiProvider：真实API调用
 * - MockAiProvider：测试/Demo模式
 * - AiPromptBuilder：请求体构建
 * - AiResponseParser：响应解析
 */
@Service
public class AiService {

    private final AiProvider aiProvider;

    public AiService(@Value("${ai.base-url}") String baseUrl,
                     @Value("${ai.api-key:fake-key-for-testing}") String apiKey,
                     @Value("${ai.model}") String modelName) {
        // 根据 API Key 决定使用真实提供者还是 Mock 提供者
        if (isMockMode(apiKey)) {
            this.aiProvider = new MockAiProvider();
        } else {
            RestClient restClient = RestClient.builder()
                    .baseUrl(baseUrl)
                    .defaultHeader("Authorization", "Bearer " + apiKey)
                    .build();
            this.aiProvider = new RealAiProvider(restClient, new AiPromptBuilder(modelName), new AiResponseParser());
        }
    }

    private boolean isMockMode(String apiKey) {
        return apiKey == null || apiKey.isBlank() || apiKey.contains("fake") || apiKey.contains("mock");
    }

    /**
     * 生成帖子摘要（同步调用）
     * 向后兼容：保持既有的 public API 不变
     */
    public String generateSummary(String content) {
        return aiProvider.generateSummary(content);
    }

    /**
     * 进行聊天对话
     * 向后兼容：保持既有的 public API 不变
     */
    public String chatSync(List<Map<String, String>> messages) {
        return aiProvider.chat(messages);
    }
}
