package com.example.demo.notifications.outbox;

import com.example.demo.user.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = com.example.demo.Demo1Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisabledInAotMode
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:notification-outbox;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "app.notifications.outbox.lease-duration=30s",
        "app.notifications.outbox.batch-size=10",
        "app.notifications.outbox.max-attempts=3",
        "app.notifications.outbox.backoff-base=1ms",
        "app.notifications.outbox.retention=1d"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationOutboxServiceH2Test {
    @Autowired
    private NotificationOutboxRepository repository;

    @Autowired
    private NotificationOutboxService outboxService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private UserSettingsService userSettingsService;

    @BeforeEach
    void allowPreferencesAndStartClean() {
        when(userSettingsService.shouldDeliver(anyLong(), anyString())).thenReturn(true);
        repository.deleteAll();
    }

    @Test
    void enqueue_commits_and_can_be_read_back_by_event_id() {
        UUID eventId = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> outboxService.enqueue(
                eventId, 101L, "reply", "Title", "Content", 7L, 8L, "post").orElseThrow());

        NotificationOutbox row = repository.findByEventId(eventId.toString()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(row.getRecipientUserId()).isEqualTo(101L);
        assertThat(row.getAttempts()).isZero();
    }

    @Test
    void enqueue_rolls_back_with_the_callers_transaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            outboxService.enqueue(UUID.randomUUID(), 101L, "reply", "Title", "Content", null, null, "post");
            throw new IllegalStateException("simulate primary write failure");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(repository.count()).isZero();
    }

    @Test
    void preference_off_does_not_enqueue() {
        when(userSettingsService.shouldDeliver(101L, "reply")).thenReturn(false);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        Optional<NotificationOutbox> result = transaction.execute(status -> outboxService.enqueue(
                UUID.randomUUID(), 101L, "reply", "Title", "Content", null, null, "post"));

        assertThat(result).isEmpty();
        assertThat(repository.count()).isZero();
    }

    @Test
    void two_workers_can_claim_a_row_only_once() throws Exception {
        savePending();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Callable<Optional<NotificationOutboxService.Delivery>> claim =
                    () -> outboxService.claimOne(UUID.randomUUID().toString());
            List<Future<Optional<NotificationOutboxService.Delivery>>> results = workers.invokeAll(
                    List.of(claim, claim), 5, TimeUnit.SECONDS);
            assertThat(results).allMatch(result -> !result.isCancelled());

            long successfulClaims = 0;
            for (Future<Optional<NotificationOutboxService.Delivery>> result : results) {
                if (result.get().isPresent()) {
                    successfulClaims++;
                }
            }
            assertThat(successfulClaims).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void expired_lease_can_be_reclaimed_but_old_owner_cannot_complete_it() {
        savePending();
        NotificationOutboxService.Delivery first = outboxService.claimOne("owner-a").orElseThrow();
        jdbcTemplate.update(
                "UPDATE notification_outbox SET lease_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)),
                first.id()
        );

        NotificationOutboxService.Delivery second = outboxService.claimOne("owner-b").orElseThrow();

        assertThat(outboxService.markDelivered(first.id(), "owner-a")).isFalse();
        assertThat(outboxService.markDelivered(second.id(), "owner-b")).isTrue();
        assertThat(repository.findById(first.id()).orElseThrow().getStatus())
                .isEqualTo(NotificationOutboxStatus.DELIVERED);
    }

    @Test
    void expired_lease_is_not_starved_by_a_full_pending_candidate_page() {
        savePending();
        var expired = outboxService.claimOne("crashed-worker").orElseThrow();
        jdbcTemplate.update("UPDATE notification_outbox SET lease_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(2)), expired.id());
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            for (int index = 0; index < 101; index++) {
                outboxService.enqueue(UUID.randomUUID(), 101L, "reply", "Title", "Content", null, null, "post");
            }
        });
        var recovered = outboxService.claimOne("replacement-worker").orElseThrow();
        assertThat(recovered.id()).isEqualTo(expired.id());
        assertThat(outboxService.markDelivered(expired.id(), "crashed-worker")).isFalse();
        assertThat(outboxService.markDelivered(recovered.id(), "replacement-worker")).isTrue();
    }

    private void savePending() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> outboxService.enqueue(
                UUID.randomUUID(), 101L, "reply", "Title", "Content", null, null, "post").orElseThrow());
    }
}
