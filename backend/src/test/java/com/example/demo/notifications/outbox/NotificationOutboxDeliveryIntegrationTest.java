package com.example.demo.notifications.outbox;

import com.example.demo.notifications.dto.NotificationDto;
import com.example.demo.notifications.service.NotificationService;
import com.example.demo.user.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "app.notifications.client.mode=remote",
                "app.notifications.client.enabled=true",
                "app.notifications.client.internal-service-token=integration-test-token-0123456789-abcdef",
                "app.notifications.outbox.scheduling-enabled=false",
                "app.notifications.outbox.backoff-base=1ms",
                "app.notifications.outbox.lease-duration=30s",
                "app.notifications.outbox.batch-size=1",
                "spring.datasource.url=jdbc:h2:mem:outbox-delivery;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.flyway.enabled=false",
                "feature.mp.read.threads=false",
                "springdoc.api-docs.enabled=false",
                "springdoc.swagger-ui.enabled=false"
        }
)
@ActiveProfiles("test")
@DisabledInAotMode
class NotificationOutboxDeliveryIntegrationTest {
    @Autowired
    private NotificationOutboxService outboxService;

    @Autowired
    private NotificationOutboxRepository outboxRepository;

    @Autowired
    private NotificationOutboxDispatcher dispatcher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private UserSettingsService userSettingsService;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        when(userSettingsService.shouldDeliver(anyLong(), anyString())).thenReturn(true);
    }

    @Test
    void failed_delivery_stays_pending_then_retry_delivers_once_with_same_event_id() {
        UUID eventId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                outboxService.enqueue(eventId, 101L, "reply", "Title", "Content", 7L, 8L, "post")
                        .orElseThrow());

        when(notificationService.create(
                eq(101L), eq("reply"), eq("Title"), eq("Content"), eq(7L), eq(8L), eq("post"), eq(eventId)))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "temporary"))
                .thenReturn(new NotificationDto(
                        900L, "reply", "Title", "Content", false, Instant.now(), 7L, 8L, "post"));

        dispatcher.dispatch();

        NotificationOutbox afterFailure = outboxRepository.findByEventId(eventId.toString()).orElseThrow();
        assertThat(afterFailure.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(afterFailure.getAttempts()).isEqualTo(1);

        jdbcTemplate.update("UPDATE notification_outbox SET available_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), afterFailure.getId());
        dispatcher.dispatch();

        NotificationOutbox afterRetry = outboxRepository.findByEventId(eventId.toString()).orElseThrow();
        assertThat(afterRetry.getStatus()).isEqualTo(NotificationOutboxStatus.DELIVERED);
        assertThat(afterRetry.getAttempts()).isEqualTo(2);
        org.mockito.Mockito.verify(notificationService, org.mockito.Mockito.times(2)).create(
                101L, "reply", "Title", "Content", 7L, 8L, "post", eventId);
    }

    @Test
    void lost_acknowledgement_retries_the_already_accepted_event_with_the_same_key() {
        UUID eventId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                outboxService.enqueue(eventId, 101L, "reply", "Title", "Content", 7L, 8L, "post").orElseThrow());
        var acceptedEvents = new java.util.HashSet<UUID>();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        // Transport double accepts the first event but loses its acknowledgement.
        // Receiver database deduplication is verified separately in notification-service.
        when(notificationService.create(101L, "reply", "Title", "Content", 7L, 8L, "post", eventId))
                .thenAnswer(invocation -> {
                    acceptedEvents.add(invocation.getArgument(7, UUID.class));
                    if (attempts.incrementAndGet() == 1) {
                        throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                                "acknowledgement lost after acceptance");
                    }
                    return new NotificationDto(900L, "reply", "Title", "Content", false,
                            Instant.now(), 7L, 8L, "post");
                });
        dispatcher.dispatch();
        var row = outboxRepository.findByEventId(eventId.toString()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        jdbcTemplate.update("UPDATE notification_outbox SET available_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), row.getId());
        dispatcher.dispatch();
        var completed = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(NotificationOutboxStatus.DELIVERED);
        assertThat(completed.getAttempts()).isEqualTo(2);
        assertThat(acceptedEvents).containsExactly(eventId);
    }
}
