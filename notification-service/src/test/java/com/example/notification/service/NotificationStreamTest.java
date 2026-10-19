package com.example.notification.service;

import com.example.notification.dto.CreateNotificationRequest;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisabledInAotMode
class NotificationStreamTest {
    private static final String SECRET = "stream-" + UUID.randomUUID() + UUID.randomUUID();
    private static final String DATABASE = "stream_" + UUID.randomUUID();
    @Autowired NotificationEventLog events;
    @Autowired PersistentNotificationService notifications;
    @Autowired PlatformTransactionManager transactions;
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:" + DATABASE + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        properties.add("app.security.jwt.secret", () -> SECRET);
    }

    @Test
    void idempotentCreationAndReadPublishDurableOwnerEvents() {
        var request = request(901L);
        var created = notifications.create(request);
        notifications.create(request);
        assertThat(events.after(901L, 0, 100)).hasSize(1);
        notifications.markRead(901L, created.getId());
        notifications.markRead(901L, created.getId());
        assertThat(events.after(901L, 0, 100)).extracting(NotificationEventLog.Event::kind)
                .containsExactly("created", "read");
        assertThat(events.after(902L, 0, 100)).isEmpty();
    }

    @Test
    void sequenceRollbackAndCommitOrderingCannotLeaveCursorGaps() throws Exception {
        var created = notifications.create(request(903L));
        new TransactionTemplate(transactions).execute(status -> {
            events.append(903L, created.getId(), "read");
            status.setRollbackOnly();
            return null;
        });
        assertThat(events.current(903L)).isEqualTo(1);
        var firstHasLock = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                events.append(903L, created.getId(), "read");
                firstHasLock.countDown();
                try { assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                return null;
            }));
            assertThat(firstHasLock.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                events.append(903L, created.getId(), "read");
                return null;
            }));
            assertThat(events.after(903L, 1, 100)).isEmpty();
            releaseFirst.countDown();
            first.get(8, TimeUnit.SECONDS);
            second.get(8, TimeUnit.SECONDS);
        } finally { releaseFirst.countDown(); }
        assertThat(events.after(903L, 0, 100)).extracting(NotificationEventLog.Event::cursor)
                .containsExactly("1", "2", "3");
    }

    @Test
    void realHttpStreamReplaysOnlyOwnerAndClosesAtTokenExpiry() throws Exception {
        var created = notifications.create(request(904L));
        notifications.create(request(905L));
        var response = http.send(request("/stream", token(904L, 3)).header("Last-Event-ID", "0").build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control").orElse("")).contains("no-store");
        try (InputStream body = response.body(); var pool = Executors.newSingleThreadExecutor()) {
            String text = pool.submit(() -> new String(body.readAllBytes(), StandardCharsets.UTF_8)).get(9, TimeUnit.SECONDS);
            assertThat(text).contains("event:notification", "\"notificationId\":\"" + created.getId() + "\"");
            assertThat(text).doesNotContain("\"cursor\":\"2\"");
        }
        var foreign = http.send(request("/events?after=0", token(906L, 30)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(foreign.statusCode()).isEqualTo(200);
        assertThat(foreign.body()).isEqualTo("[]");
    }

    @Test
    void anonymousExpiredAndInvalidCursorRequestsAreRejected() throws Exception {
        assertThat(http.send(request("/stream", null).build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(http.send(request("/stream", token(907L, -2)).build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(http.send(request("/stream", token(907L, 30)).header("Last-Event-ID", "-1").build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        assertThat(http.send(request("/stream", token(907L, 30)).header("Last-Event-ID", "1").build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
    }

    private HttpRequest.Builder request(String suffix, String token) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/notifications" + suffix)).timeout(Duration.ofSeconds(12));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request;
    }

    private String token(long user, long expiresIn) {
        return Jwts.builder().subject(Long.toString(user)).issuer("bluealbum-notification-service-test")
                .expiration(Date.from(Instant.now().plusSeconds(expiresIn)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private CreateNotificationRequest request(Long owner) {
        var request = new CreateNotificationRequest();
        request.setEventId(UUID.randomUUID()); request.setUserId(owner); request.setType("system");
        request.setTitle("图片状态"); request.setContent("处理状态发生变化");
        return request;
    }
}
