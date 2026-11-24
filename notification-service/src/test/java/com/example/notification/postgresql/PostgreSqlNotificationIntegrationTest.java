package com.example.notification.postgresql;

import com.example.notification.dto.CreateNotificationRequest;
import com.example.notification.dto.NotificationDto;
import com.example.notification.service.NotificationEventLog;
import com.example.notification.service.PersistentNotificationService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Opt-in PostgreSQL evidence. Pass the three notification.postgres.test.*
 * system properties and build with the Maven postgresql profile; normal Maven
 * test runs skip this class without requiring an external service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("postgresql")
@DisabledInAotMode
@EnabledIfSystemProperty(named = "notification.postgres.test.url", matches = "\\S+")
class PostgreSqlNotificationIntegrationTest {
    private static final String URL_PROPERTY = "notification.postgres.test.url";
    private static final String USER_PROPERTY = "notification.postgres.test.username";
    private static final String PASSWORD_PROPERTY = "notification.postgres.test.password";
    private static final String SECRET = "postgres-notification-test-secret-0123456789";
    private static final String ISSUER = "bluealbum-notification-service-postgresql-test";

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PersistentNotificationService notifications;

    @Autowired
    private NotificationEventLog events;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> required(URL_PROPERTY));
        properties.add("spring.datasource.username", () -> required(USER_PROPERTY));
        properties.add("spring.datasource.password", () -> required(PASSWORD_PROPERTY));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.flyway.database-type", () -> "postgresql");
        properties.add("spring.flyway.locations", () -> "classpath:db/postgresql");
        properties.add("app.security.jwt.secret", () -> SECRET);
        properties.add("app.security.jwt.issuer", () -> ISSUER);
    }

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM notification_stream_event");
        jdbc.update("DELETE FROM notification_stream_cursor");
        jdbc.update("DELETE FROM notifications");
    }

    @Test
    void startupRunsPostgresMigrationsAndUsesGeneratedIdentity() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
        assertThat(jdbc.queryForObject(
                "SELECT is_identity FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = 'notifications' AND column_name = 'id'",
                String.class
        )).isEqualTo("YES");
        assertThat(jdbc.queryForObject(
                "SELECT datetime_precision FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = 'notifications' AND column_name = 'created_at'",
                Integer.class
        )).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT datetime_precision FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = 'notification_stream_event' AND column_name = 'occurred_at'",
                Integer.class
        )).isEqualTo(6);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = current_schema() AND table_name = 'notification_stream_event' AND constraint_type = 'FOREIGN KEY'",
                Integer.class
        )).isEqualTo(1);

        NotificationDto created = notifications.create(request(1_000L, UUID.randomUUID(), "generated"));
        assertThat(created.getId()).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateCreatesKeepOneReceiverEventAndDoNotPoisonTransactions() throws Exception {
        UUID eventId = UUID.randomUUID();
        CreateNotificationRequest request = request(1_001L, eventId, "duplicate");
        List<NotificationDto> results = invokeConcurrently(List.of(request, request, request, request, request, request));

        assertThat(results).hasSize(6).doesNotContainNull();
        assertThat(results).extracting(NotificationDto::getId).containsOnly(results.getFirst().getId());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE idempotency_key = ?", Integer.class, eventId.toString()
        )).isEqualTo(1);
        assertThat(events.after(1_001L, 0, 100)).extracting(NotificationEventLog.Event::kind)
                .containsExactly("created");

        CreateNotificationRequest mismatch = request(1_001L, eventId, "mismatch");
        assertThatThrownBy(() -> notifications.create(mismatch))
                .isInstanceOf(ResponseStatusException.class);
        NotificationDto subsequent = notifications.create(request(1_002L, UUID.randomUUID(), "after-conflict"));
        assertThat(subsequent.getId()).isPositive();
    }

    @Test
    void concurrentOwnersEventsHaveContiguousCommitOrder() throws Exception {
        List<Callable<NotificationDto>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int sequence = i;
            calls.add(() -> notifications.create(request(1_003L, UUID.randomUUID(), "ordered-" + sequence)));
        }
        try (ExecutorService workers = Executors.newFixedThreadPool(calls.size())) {
            List<java.util.concurrent.Future<NotificationDto>> futures = workers.invokeAll(calls, 15, TimeUnit.SECONDS);
            for (var future : futures) {
                assertThat(future.isCancelled()).isFalse();
                assertThat(future.get()).isNotNull();
            }
        }

        assertThat(events.after(1_003L, 0, 100)).extracting(NotificationEventLog.Event::cursor)
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
    }

    @Test
    void markReadIsDurableAndSseReplaysCreatedAndReadEvents() throws Exception {
        long owner = 1_004L;
        NotificationDto created = notifications.create(request(owner, UUID.randomUUID(), "sse"));
        assertThat(notifications.markRead(owner, created.getId()).isRead()).isTrue();
        assertThat(events.after(owner, 0, 100)).extracting(NotificationEventLog.Event::kind)
                .containsExactly("created", "read");

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/notifications/stream"))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer " + token(owner, 3))
                .header("Last-Event-ID", "0")
                .GET()
                .build();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        try (InputStream body = response.body(); ExecutorService reader = Executors.newSingleThreadExecutor()) {
            String text = reader.submit(() -> new String(body.readAllBytes(), StandardCharsets.UTF_8))
                    .get(9, TimeUnit.SECONDS);
            assertThat(text).contains("event:notification");
            assertThat(text).contains("\"notificationId\":\"" + created.getId() + "\"");
            assertThat(text).contains("\"kind\":\"created\"");
            assertThat(text).contains("\"kind\":\"read\"");
        }
    }

    private List<NotificationDto> invokeConcurrently(List<CreateNotificationRequest> requests) throws Exception {
        try (ExecutorService workers = Executors.newFixedThreadPool(requests.size())) {
            List<Callable<NotificationDto>> calls = requests.stream().<Callable<NotificationDto>>map(
                    request -> () -> notifications.create(request)
            ).toList();
            List<java.util.concurrent.Future<NotificationDto>> futures = workers.invokeAll(calls, 15, TimeUnit.SECONDS);
            List<NotificationDto> results = new ArrayList<>();
            for (var future : futures) {
                assertThat(future.isCancelled()).isFalse();
                results.add(future.get());
            }
            return results;
        }
    }

    private CreateNotificationRequest request(long owner, UUID eventId, String title) {
        CreateNotificationRequest request = new CreateNotificationRequest();
        request.setUserId(owner);
        request.setType("system");
        request.setTitle(title);
        request.setContent("PostgreSQL integration");
        request.setThreadId(7L);
        request.setTargetId(8L);
        request.setTargetType("post");
        request.setEventId(eventId);
        return request;
    }

    private String token(long owner, long expiresInSeconds) {
        return Jwts.builder().subject(Long.toString(owner)).issuer(ISSUER)
                .expiration(Date.from(Instant.now().plusSeconds(expiresInSeconds)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing -D" + property);
        }
        return value;
    }
}
