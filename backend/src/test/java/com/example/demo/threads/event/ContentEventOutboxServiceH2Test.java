package com.example.demo.threads.event;

import com.example.demo.threads.event.entity.ContentEventOutbox;
import com.example.demo.threads.event.entity.ContentEventStatus;
import com.example.demo.threads.event.entity.ContentEventType;
import com.example.demo.threads.event.repo.ContentEventOutboxRepository;
import com.example.demo.threads.event.service.ContentEventOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = com.example.demo.Demo1Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisabledInAotMode
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:content-event-delivery;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "app.rabbit.enabled=false",
        "app.content.events.enabled=false",
        "app.content.events.batch-size=20",
        "app.content.events.max-attempts=2",
        "app.content.events.backoff-base=1ms",
        "app.content.events.lease-duration=30s"
})
class ContentEventOutboxServiceH2Test {
    @Autowired
    private ContentEventOutboxRepository repository;

    @Autowired
    private ContentEventOutboxService service;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void two_workers_claim_a_due_row_once_and_published_guard_clears_lease() throws Exception {
        ContentEventOutbox pending = savePending();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = workers.submit(() -> service.claimOne("worker-one"));
            Future<?> second = workers.submit(() -> service.claimOne("worker-two"));
            var firstResult = first.get(5, TimeUnit.SECONDS);
            var secondResult = second.get(5, TimeUnit.SECONDS);
            long claims = ((java.util.Optional<?>) firstResult).isPresent() ? 1 : 0;
            claims += ((java.util.Optional<?>) secondResult).isPresent() ? 1 : 0;
            assertThat(claims).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }

        ContentEventOutbox row = repository.findById(pending.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ContentEventStatus.IN_FLIGHT);
        assertThat(row.getAttempts()).isEqualTo(1);
        String owner = row.getLeaseOwner();
        assertThat(service.markPublished(pending.getId(), owner)).isTrue();
        row = repository.findById(pending.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ContentEventStatus.PUBLISHED);
        assertThat(row.getLeaseOwner()).isNull();
        assertThat(row.getPublishedAt()).isNotNull();
    }

    @Test
    void expired_owner_cannot_complete_a_reclaimed_row_and_failure_is_bounded() {
        ContentEventOutbox pending = savePending();
        var first = service.claimOne("old-owner").orElseThrow();
        jdbcTemplate.update("UPDATE content_event_outbox SET lease_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), pending.getId());
        var second = service.claimOne("new-owner").orElseThrow();

        assertThat(service.markPublished(first.id(), first.owner())).isFalse();
        assertThat(service.markFailure(second, new IllegalStateException("hidden"))).isTrue();
        ContentEventOutbox failed = repository.findById(pending.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ContentEventStatus.FAILED);
        assertThat(failed.getLastError()).isEqualTo("IllegalStateException");

        assertThat(service.manualRetry(pending.getId())).isTrue();
        ContentEventOutbox retried = repository.findById(pending.getId()).orElseThrow();
        assertThat(retried.getStatus()).isEqualTo(ContentEventStatus.PENDING);
        assertThat(retried.getAttempts()).isZero();

        jdbcTemplate.update("UPDATE content_event_outbox SET available_at = ? WHERE id = ?",
                Timestamp.from(Instant.EPOCH), pending.getId());
        var retry = service.claimOne("third-owner").orElseThrow();
        assertThat(retry.attempts()).isEqualTo(1);
    }

    @Test
    void an_expired_row_at_max_attempts_is_failed_without_a_new_claim() {
        ContentEventOutbox pending = savePending();
        jdbcTemplate.update("""
                UPDATE content_event_outbox
                   SET status = 'IN_FLIGHT', attempts = 2, lease_owner = 'crashed-worker',
                       lease_until = ?, available_at = ?
                 WHERE id = ?
                """, Timestamp.from(Instant.now().minusSeconds(1)),
                Timestamp.from(Instant.EPOCH), pending.getId());

        assertThat(service.claimOne("replacement-worker")).isEmpty();
        ContentEventOutbox failed = repository.findById(pending.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ContentEventStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(2);
        assertThat(failed.getLastError()).isEqualTo("MAX_ATTEMPTS");
    }

    private ContentEventOutbox savePending() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        ContentEventOutbox row = new ContentEventOutbox();
        row.setEventId(UUID.randomUUID().toString());
        row.setSchemaVersion(1);
        row.setAggregateType("THREAD");
        row.setAggregateId(1L);
        row.setAggregateVersion(System.nanoTime());
        row.setEventType(ContentEventType.THREAD_UPSERTED);
        row.setOccurredAt(Instant.now());
        row.setStatus(ContentEventStatus.PENDING);
        row.setAttempts(0);
        row.setAvailableAt(Instant.EPOCH);
        return transaction.execute(status -> repository.saveAndFlush(row));
    }
}
