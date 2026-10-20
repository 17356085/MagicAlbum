package com.example.demo.ai.summary;

import com.example.demo.ai.summary.service.SummaryTaskPublisher;
import com.example.demo.ai.summary.service.SummaryTaskRunner;
import com.example.demo.ai.summary.service.SummaryTaskService;
import com.example.demo.sections.entity.Section;
import com.example.demo.sections.repo.SectionRepository;
import com.example.demo.threads.dto.CreateThreadRequest;
import com.example.demo.threads.service.ThreadService;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Explicit-only external model acceptance. Not included in normal Surefire naming patterns. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisabledInAotMode
class SummaryRealModelAcceptance {
    static final ObjectMapper JSON = new ObjectMapper();
    @Autowired SummaryTaskService tasks;
    @Autowired SummaryTaskRunner runner;
    @Autowired ThreadService threads;
    @Autowired SectionRepository sections;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SummaryTaskPublisher publisher;

    @DynamicPropertySource
    static void privateConfiguration(DynamicPropertyRegistry registry) {
        String base = required("REAL_AI_BASE_URL");
        URI uri;
        try { uri = URI.create(base); } catch (RuntimeException ignored) { throw new IllegalStateException("Invalid external provider configuration"); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getHost().equals("localhost") || uri.getHost().matches("[0-9.]+")
                || uri.getHost().endsWith(".invalid") || uri.getUserInfo() != null || uri.getQuery() != null) {
            throw new IllegalStateException("External acceptance requires a public HTTPS provider");
        }
        registry.add("ai.provider", () -> "real");
        registry.add("ai.base-url", () -> base);
        registry.add("ai.api-key", () -> required("REAL_AI_API_KEY"));
        registry.add("ai.model", () -> required("REAL_AI_MODEL"));
        registry.add("ai.request-timeout-seconds", () -> "120");
        registry.add("spring.datasource.url", SummaryRealModelAcceptance::databaseUrl);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("spring.jpa.show-sql", () -> "false");
        registry.add("logging.level.org.hibernate.SQL", () -> "WARN");
        registry.add("app.rabbit.enabled", () -> "false");
    }

    @Test
    void externalNormalAndLongInputCompleteThroughVersionedTaskAndCommittedSqlReadback() throws Exception {
        when(publisher.available()).thenReturn(true);
        String nonce = UUID.randomUUID().toString();
        User user = new User(); user.setUsername("external-" + nonce); user.setEmail(nonce + "@test.invalid");
        user.setPhone(nonce.substring(0, 20)); user.setPasswordHash("acceptance-only");
        Long actor = users.saveAndFlush(user).getId();
        Section section = new Section(); section.setName("外部摘要验收"); section.setSlug("external-" + nonce);
        section = sections.saveAndFlush(section);
        String normal = "同频社区计划在周六上午十点举办旧书交换活动，地点是学校图书馆一楼。"
                + "参加者每人最多携带三本完整、无明显破损的书，现场不涉及现金交易。"
                + "志愿者负责登记书名并协调交换，活动在中午十二点结束；遇到暴雨则延期一天。";
        String paragraph = "社区志愿者讨论怎样让旧书交换更方便。参与者需要提前整理书目，说明书籍状况，尊重彼此选择。"
                + "现场要保留清晰通道，纸质登记只记录书名和交换结果，不收集联系方式。";
        String longInput = "这篇长文记录旧书交换活动的组织方案。" + paragraph.repeat(160);
        longInput = longInput.substring(0, 8800) + "最后决定：活动改为周日下午两点开始，地点调整到体育馆东门，报名截止周五。";
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (var source : List.of(Map.entry("normal", normal), Map.entry("long", longInput))) {
            CreateThreadRequest request = new CreateThreadRequest();
            request.setSectionId(section.getId()); request.setTitle("外部模型验收 " + source.getKey()); request.setContent(source.getValue());
            Long thread = threads.create(actor, request).getId();
            var pending = tasks.start(thread, actor, UUID.randomUUID().toString(), false);
            runner.execute(pending.taskId());
            var result = tasks.read(thread, actor);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("case", source.getKey()); row.put("threadId", thread); row.put("taskId", result.taskId());
            row.put("contentVersion", result.contentVersion()); row.put("inputChars", source.getValue().length());
            row.put("inputSha256", sha(source.getValue())); row.put("summary", result.summary()); row.put("summarySha256", sha(result.summary()));
            row.put("provider", result.provider()); row.put("model", result.model()); row.put("requestId", result.requestId());
            row.put("attempts", result.attempts()); row.put("chargedTokens", result.chargedTokens()); row.put("usageEstimated", result.usageEstimated());
            row.put("inputTokens", result.inputTokens()); row.put("outputTokens", result.outputTokens());
            row.put("status", result.status()); row.put("errorCode", result.errorCode());
            row.put("attemptEvidence", jdbc.queryForList("SELECT attempt_number,provider_request_id,provider_response_id,response_model,duration_millis,charged_tokens,usage_estimated,status,error_code FROM ai_summary_attempt WHERE task_id=? ORDER BY attempt_number", result.taskId()));
            evidence.add(row);
            JSON.writerWithDefaultPrettyPrinter().writeValue(output().resolve("external-model.json").toFile(),
                    Map.of("providerMode", "real", "controlledFixture", false, "databaseKind", "isolated-file-H2",
                            "fullMysqlRabbitBrowserProven", false, "cases", evidence));
            if (!"COMPLETED".equals(result.status())) throw new AssertionError("External summary incomplete: " + result.errorCode());
            assertThat(result.summary()).isNotBlank();
            assertThat(result.provider()).isNotEqualTo("mock").isNotEqualTo("127.0.0.1");
            assertThat(result.model()).isEqualTo(required("REAL_AI_MODEL"));
            assertThat(result.attempts()).isBetween(1, 3);
            assertThat(jdbc.queryForObject("SELECT summary FROM threads WHERE id=?", String.class, thread)).isEqualTo(result.summary());
        }
    }

    static Path output() {
        Path path = Path.of(required("REAL_AI_OUTPUT_DIR")).toAbsolutePath().normalize();
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (root != null && !Files.isDirectory(root.resolve("frontend"))) root = root.getParent();
        if (root == null || !path.startsWith(root.resolve("outputs")) || !Files.isDirectory(path)) {
            throw new IllegalStateException("External acceptance output must be an existing workspace outputs folder");
        }
        return path;
    }
    static String databaseUrl() { return "jdbc:h2:file:" + output().resolve("summary-runtime").toString().replace('\\', '/') + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_ON_EXIT=FALSE"; }
    static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing required acceptance environment: " + key);
        return value;
    }
    static String sha(String value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
}
