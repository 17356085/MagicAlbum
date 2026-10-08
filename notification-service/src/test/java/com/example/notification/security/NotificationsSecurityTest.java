package com.example.notification.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisabledInAotMode
class NotificationsSecurityTest {
    private static final String JWT_SECRET = "test-" + UUID.randomUUID() + "-" + UUID.randomUUID();
    private static final String JWT_ISSUER = "notification-test";
    private static final String INTERNAL_CREDENTIAL = "service-" + UUID.randomUUID() + "-" + UUID.randomUUID();
    private static final SecretKey JWT_KEY = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
    private static final SecretKey OTHER_JWT_KEY = Keys.hmacShaKeyFor(
            ("other-" + UUID.randomUUID() + "-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)
    );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long userANotificationId;
    private long userBNotificationId;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) {
        registry.add("app.security.jwt.secret", () -> JWT_SECRET);
        registry.add("app.security.jwt.issuer", () -> JWT_ISSUER);
        registry.add("app.security.internal-service-token", () -> INTERNAL_CREDENTIAL);
    }

    @BeforeEach
    void seedNotifications() {
        jdbcTemplate.update("DELETE FROM notifications");
        userANotificationId = insertNotification(101L, "A");
        userBNotificationId = insertNotification(202L, "B");
    }

    @Test
    void anonymous_get_is_rejected() throws Exception {
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void anonymous_patch_is_rejected() throws Exception {
        mockMvc.perform(patch("/api/v1/notifications/{id}/read", userANotificationId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalid_expired_or_non_expiring_jwt_is_rejected() throws Exception {
        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", bearerToken(101L, "wrong-issuer", Instant.now().plusSeconds(300))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", bearerToken(101L, JWT_ISSUER, Instant.now().plusSeconds(300), OTHER_JWT_KEY)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", bearerToken(101L, JWT_ISSUER, Instant.now().minusSeconds(1))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", bearerTokenWithoutExpiration(101L)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void user_a_cannot_read_or_update_user_b_notification() throws Exception {
        mockMvc.perform(get("/api/v1/notifications")
                .header("Authorization", bearerToken(101L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(userANotificationId));

        mockMvc.perform(patch("/api/v1/notifications/{id}/read", userBNotificationId)
                        .header("Authorization", bearerToken(101L)))
                .andExpect(status().isNotFound());
    }

    @Test
    void forged_user_header_does_not_change_verified_principal() throws Exception {
        mockMvc.perform(get("/api/v1/notifications")
                        .header("X-User-Id", "202"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", bearerToken(101L))
                        .header("X-User-Id", "202"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(userANotificationId));

        mockMvc.perform(patch("/api/v1/notifications/{id}/read", userBNotificationId)
                        .header("Authorization", bearerToken(101L))
                        .header("X-User-Id", "202"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalid_pagination_is_mapped_to_bad_request() throws Exception {
        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", bearerToken(101L))
                        .param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void ordinary_user_jwt_cannot_call_internal_endpoint() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/internal")
                        .header("Authorization", bearerToken(101L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notificationJson(303L)))
                .andExpect(status().isForbidden());
    }

    @Test
    void missing_or_wrong_internal_credential_is_rejected() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/internal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notificationJson(303L)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/notifications/internal")
                        .header(InternalServiceAuthenticationFilter.INTERNAL_CREDENTIAL_HEADER, "wrong-credential")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notificationJson(303L)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void correct_internal_credential_can_create_notification() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/internal")
                        .header(InternalServiceAuthenticationFilter.INTERNAL_CREDENTIAL_HEADER, INTERNAL_CREDENTIAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notificationJson(303L)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("system"))
                .andExpect(jsonPath("$.title").value("Created internally"));
    }

    @Test
    void target_metadata_round_trips_through_create_list_and_mark_read() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/internal")
                        .header(InternalServiceAuthenticationFilter.INTERNAL_CREDENTIAL_HEADER, INTERNAL_CREDENTIAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notificationJson(303L, 404L, 505L, "post")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.threadId").value(404))
                .andExpect(jsonPath("$.targetId").value(505))
                .andExpect(jsonPath("$.targetType").value("post"))
                .andExpect(jsonPath("$.link").value("/threads/404#post-505"));

        long notificationId = jdbcTemplate.queryForObject(
                "SELECT id FROM notifications WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                Long.class,
                303L
        );

        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", bearerToken(303L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].threadId").value(404))
                .andExpect(jsonPath("$.items[0].targetId").value(505))
                .andExpect(jsonPath("$.items[0].targetType").value("post"))
                .andExpect(jsonPath("$.items[0].link").value("/threads/404#post-505"));

        mockMvc.perform(patch("/api/v1/notifications/{id}/read", notificationId)
                        .header("Authorization", bearerToken(303L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true))
                .andExpect(jsonPath("$.threadId").value(404))
                .andExpect(jsonPath("$.targetId").value(505))
                .andExpect(jsonPath("$.targetType").value("post"))
                .andExpect(jsonPath("$.link").value("/threads/404#post-505"));
    }

    @Test
    void invalid_target_metadata_is_rejected() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/internal")
                        .header(InternalServiceAuthenticationFilter.INTERNAL_CREDENTIAL_HEADER, INTERNAL_CREDENTIAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notificationJson(303L, 404L, 0L, "post")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/notifications/internal")
                        .header(InternalServiceAuthenticationFilter.INTERNAL_CREDENTIAL_HEADER, INTERNAL_CREDENTIAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notificationJson(303L, 404L, 505L, "external")))
                .andExpect(status().isBadRequest());
    }

    private long insertNotification(long userId, String suffix) {
        jdbcTemplate.update(
                "INSERT INTO notifications (user_id, type, title, content, read_flag, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                userId,
                "system",
                "Notification " + suffix,
                "Content " + suffix,
                false,
                Timestamp.from(Instant.now())
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM notifications WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                Long.class,
                userId
        );
    }

    private String bearerToken(long userId) {
        Instant now = Instant.now();
        return bearerToken(userId, JWT_ISSUER, now.plusSeconds(300));
    }

    private String bearerToken(long userId, String issuer, Instant expiration) {
        return bearerToken(userId, issuer, expiration, JWT_KEY);
    }

    private String bearerToken(long userId, String issuer, Instant expiration, SecretKey signingKey) {
        Instant now = Instant.now();
        return "Bearer " + Jwts.builder()
                .subject(Long.toString(userId))
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .signWith(signingKey)
                .compact();
    }

    private String bearerTokenWithoutExpiration(long userId) {
        return "Bearer " + Jwts.builder()
                .subject(Long.toString(userId))
                .issuer(JWT_ISSUER)
                .issuedAt(Date.from(Instant.now()))
                .signWith(JWT_KEY)
                .compact();
    }

    private String notificationJson(long userId) {
        return notificationJson(userId, null, null, null);
    }

    private String notificationJson(long userId, Long threadId, Long targetId, String targetType) {
        StringBuilder json = new StringBuilder("{\"userId\":").append(userId)
                .append(",\"type\":\"system\",\"title\":\"Created internally\",\"content\":\"Created by a trusted service\"");
        if (threadId != null) {
            json.append(",\"threadId\":").append(threadId);
        }
        if (targetId != null) {
            json.append(",\"targetId\":").append(targetId);
        }
        if (targetType != null) {
            json.append(",\"targetType\":\"").append(targetType).append("\"");
        }
        return json.append('}').toString();
    }
}
