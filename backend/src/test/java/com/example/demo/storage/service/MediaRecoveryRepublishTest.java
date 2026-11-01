package com.example.demo.storage.service;

import com.example.demo.Demo1Application;
import com.example.demo.storage.dto.MediaClaimResponse;
import com.example.demo.storage.dto.MediaRequestEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 baseline for media recovery and republish lease generations.
 *
 * <p>The publisher is represented only by the real outbox claim/published
 * transactions. This is not evidence that RabbitMQ accepted a message.</p>
 */
@SpringBootTest(classes = Demo1Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisabledInAotMode
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "app.media.processing-enabled=false",
        "app.rabbit.enabled=false",
        "spring.task.scheduling.enabled=false"
})
class MediaRecoveryRepublishTest {

    private static final String DATABASE_NAME = "media_recovery_republish_"
            + UUID.randomUUID().toString().replace("-", "");
    private static final String SHA = "a".repeat(64);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MediaDeliveryTransactions deliveries;

    @Autowired
    private MediaJobService jobs;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:" + DATABASE_NAME
                + ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false");
    }

    @BeforeEach
    void clearDatabase() {
        jdbc.update("DELETE FROM media_request_outbox");
        jdbc.update("DELETE FROM media_processing_job");
        jdbc.update("DELETE FROM media_asset");
    }

    @Test
    void sameExpiredProcessingLeaseDoesNotRepublishAlreadyPublishedOutbox() {
        Fixture fixture = fixture();
        claimWorkerThenPublishAndExpire(fixture);

        Instant recoveryStartedAt = Instant.now();
        deliveries.recover(fixture.jobId());
        assertThat(outboxStatus(fixture.eventId())).isEqualTo("PENDING");
        OutboxSnapshot firstRecovery = outbox(fixture.eventId());
        assertThat(firstRecovery.processingRecoveryLeaseHash())
                .isEqualTo(jobLeaseHash(fixture.jobId()));
        assertThat(firstRecovery.processingRecoveryNextAt())
                .isAfter(recoveryStartedAt.plusSeconds(119));

        MediaDeliveryTransactions.Delivery delivery = awaitRecoveredDelivery(fixture.eventId());
        assertThat(deliveries.published(delivery)).isTrue();
        assertThat(outboxStatus(fixture.eventId())).isEqualTo("PUBLISHED");

        // The job lease is still the same expired lease. A second recovery pass
        // must not turn the already published row back into PENDING.
        deliveries.recover(fixture.jobId());

        assertThat(outboxStatus(fixture.eventId())).isEqualTo("PUBLISHED");
    }

    @Test
    void cooldownSuppressionDoesNotTouchProcessingJobTimestamp() {
        Fixture fixture = fixture();
        claimWorkerThenPublishAndExpire(fixture);
        deliveries.recover(fixture.jobId());
        assertThat(deliveries.published(awaitRecoveredDelivery(fixture.eventId()))).isTrue();

        Instant oldUpdatedAt = Instant.parse("2001-01-01T00:00:00Z");
        List<Instant> observed = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            jdbc.update("UPDATE media_processing_job SET updated_at=? WHERE job_id=?",
                    Timestamp.from(oldUpdatedAt), fixture.jobId());
            deliveries.recover(fixture.jobId());
            observed.add(jobUpdatedAt(fixture.jobId()));
        }

        assertThat(outboxStatus(fixture.eventId())).isEqualTo("PUBLISHED");
        assertThat(observed).containsOnly(oldUpdatedAt);
    }

    @Test
    void successfulProcessingRecoveryStillUpdatesJobTimestamp() {
        Fixture fixture = fixture();
        claimWorkerThenPublishAndExpire(fixture);
        Instant oldUpdatedAt = Instant.parse("2001-01-01T00:00:00Z");
        jdbc.update("UPDATE media_processing_job SET updated_at=? WHERE job_id=?",
                Timestamp.from(oldUpdatedAt), fixture.jobId());

        deliveries.recover(fixture.jobId());

        assertThat(outboxStatus(fixture.eventId())).isEqualTo("PENDING");
        assertThat(jobUpdatedAt(fixture.jobId())).isAfter(oldUpdatedAt);
    }

    @Test
    void stalePendingJobStillDefersRescanWhenOutboxIsFailed() {
        Fixture fixture = fixture();
        MediaDeliveryTransactions.Delivery delivery = deliveries.claim().orElseThrow();
        deliveries.failed(delivery);
        Instant oldUpdatedAt = Instant.parse("2001-01-01T00:00:00Z");
        jdbc.update("UPDATE media_processing_job SET updated_at=? WHERE job_id=?",
                Timestamp.from(oldUpdatedAt), fixture.jobId());

        deliveries.recover(fixture.jobId());
        Instant deferredAt = jobUpdatedAt(fixture.jobId());
        assertThat(outboxStatus(fixture.eventId())).isEqualTo("FAILED");
        assertThat(deferredAt).isAfter(oldUpdatedAt);
        deliveries.recover(fixture.jobId());
        assertThat(jobUpdatedAt(fixture.jobId())).isEqualTo(deferredAt);
    }

    @Test
    void sameLeaseCanRepublishAgainAfterCooldownWithoutSleeping() {
        Fixture fixture = fixture();
        claimWorkerThenPublishAndExpire(fixture);

        deliveries.recover(fixture.jobId());
        MediaDeliveryTransactions.Delivery delivery = awaitRecoveredDelivery(fixture.eventId());
        assertThat(deliveries.published(delivery)).isTrue();
        Instant expiredCooldown = Instant.now().minusSeconds(1);
        jdbc.update("UPDATE media_request_outbox SET processing_recovery_next_at=? WHERE event_id=?",
                Timestamp.from(expiredCooldown), fixture.eventId());

        Instant recoveryStartedAt = Instant.now();
        deliveries.recover(fixture.jobId());

        assertThat(outboxStatus(fixture.eventId())).isEqualTo("PENDING");
        OutboxSnapshot recovered = outbox(fixture.eventId());
        assertThat(recovered.processingRecoveryLeaseHash()).isEqualTo(jobLeaseHash(fixture.jobId()));
        assertThat(recovered.processingRecoveryNextAt()).isAfter(recoveryStartedAt.plusSeconds(119));
    }

    @Test
    void leaseUntilChangesWithoutLeaseTokenChangeDoesNotBypassCooldown() {
        Fixture fixture = fixture();
        claimWorkerThenPublishAndExpire(fixture);

        deliveries.recover(fixture.jobId());
        MediaDeliveryTransactions.Delivery delivery = awaitRecoveredDelivery(fixture.eventId());
        assertThat(deliveries.published(delivery)).isTrue();
        OutboxSnapshot firstRecovery = outbox(fixture.eventId());
        jdbc.update("UPDATE media_processing_job SET lease_until=? WHERE job_id=?",
                Timestamp.from(Instant.now().minusSeconds(30)), fixture.jobId());

        deliveries.recover(fixture.jobId());

        OutboxSnapshot suppressed = outbox(fixture.eventId());
        assertThat(suppressed.status()).isEqualTo("PUBLISHED");
        assertThat(suppressed.processingRecoveryLeaseHash())
                .isEqualTo(firstRecovery.processingRecoveryLeaseHash());
        assertThat(suppressed.processingRecoveryNextAt())
                .isEqualTo(firstRecovery.processingRecoveryNextAt());
    }

    @Test
    void firstExpiryOfNewLeaseIsNotSuppressedByPreviousLeaseRecovery() {
        Fixture fixture = fixture();
        claimWorkerThenPublishAndExpire(fixture);

        deliveries.recover(fixture.jobId());
        assertThat(outboxStatus(fixture.eventId())).isEqualTo("PENDING");
        MediaDeliveryTransactions.Delivery recoveredDelivery = awaitRecoveredDelivery(fixture.eventId());
        assertThat(deliveries.published(recoveredDelivery)).isTrue();

        // The worker receives the same immutable event and obtains a new lease.
        // Its first expiry is a distinct recovery opportunity.
        MediaClaimResponse secondWorkerLease = jobs.claim(fixture.jobId(), fixture.envelope());
        assertThat(secondWorkerLease.status()).isEqualTo(MediaJobService.STATUS_CLAIMED);
        assertThat(secondWorkerLease.leaseUntil()).isAfter(Instant.now());
        assertThat(jdbc.queryForObject("SELECT attempts FROM media_processing_job WHERE job_id=?",
                Integer.class, fixture.jobId())).isEqualTo(2);

        expireJobLease(fixture.jobId());
        deliveries.recover(fixture.jobId());

        assertThat(outboxStatus(fixture.eventId())).isEqualTo("PENDING");
        assertThat(outbox(fixture.eventId()).processingRecoveryLeaseHash())
                .isEqualTo(jobLeaseHash(fixture.jobId()));
    }

    @Test
    void recoveryDoesNotChangeFailedOutboxAvailableAtOrBackoff() {
        Fixture fixture = fixture();
        claimWorkerThenFailAndExpire(fixture);
        OutboxSnapshot failed = outbox(fixture.eventId());
        Instant oldUpdatedAt = Instant.parse("2001-01-01T00:00:00Z");
        jdbc.update("UPDATE media_processing_job SET updated_at=? WHERE job_id=?",
                Timestamp.from(oldUpdatedAt), fixture.jobId());

        deliveries.recover(fixture.jobId());

        OutboxSnapshot recovered = outbox(fixture.eventId());
        assertThat(recovered.status()).isEqualTo("FAILED");
        assertThat(recovered.attempts()).isEqualTo(failed.attempts());
        assertThat(recovered.availableAt()).isEqualTo(failed.availableAt());
        assertThat(recovered.lastError()).isEqualTo(failed.lastError());
        assertThat(recovered.processingRecoveryLeaseHash()).isEqualTo(failed.processingRecoveryLeaseHash());
        assertThat(recovered.processingRecoveryNextAt()).isEqualTo(failed.processingRecoveryNextAt());
        assertThat(jobUpdatedAt(fixture.jobId())).isEqualTo(oldUpdatedAt);
    }

    @Test
    void recoveryDoesNotChangePendingOutboxOrInstallMarker() {
        Fixture fixture = fixture();
        assertThat(jobs.claim(fixture.jobId(), fixture.envelope()).status())
                .isEqualTo(MediaJobService.STATUS_CLAIMED);
        expireJobLease(fixture.jobId());
        var before = outboxWithPublisherLease(fixture.eventId());
        Instant oldUpdatedAt = Instant.parse("2001-01-01T00:00:00Z");
        jdbc.update("UPDATE media_processing_job SET updated_at=? WHERE job_id=?",
                Timestamp.from(oldUpdatedAt), fixture.jobId());

        deliveries.recover(fixture.jobId());

        assertThat(outboxWithPublisherLease(fixture.eventId())).isEqualTo(before);
        assertThat(jobUpdatedAt(fixture.jobId())).isEqualTo(oldUpdatedAt);
    }

    @Test
    void recoveryDoesNotChangeAnInFlightPublisherLeaseOrInstallMarker() {
        Fixture fixture = fixture();
        MediaDeliveryTransactions.Delivery delivery = deliveries.claim().orElseThrow();
        assertThat(jobs.claim(fixture.jobId(), fixture.envelope()).status())
                .isEqualTo(MediaJobService.STATUS_CLAIMED);
        expireJobLease(fixture.jobId());
        jdbc.update("UPDATE media_request_outbox SET lease_until=? WHERE event_id=?",
                Timestamp.from(Instant.now().plusSeconds(300)), fixture.eventId());
        var before = outboxWithPublisherLease(fixture.eventId());
        Instant oldUpdatedAt = Instant.parse("2001-01-01T00:00:00Z");
        jdbc.update("UPDATE media_processing_job SET updated_at=? WHERE job_id=?",
                Timestamp.from(oldUpdatedAt), fixture.jobId());

        deliveries.recover(fixture.jobId());

        assertThat(outboxWithPublisherLease(fixture.eventId())).isEqualTo(before);
        assertThat(jobUpdatedAt(fixture.jobId())).isEqualTo(oldUpdatedAt);
        assertThat(deliveries.published(delivery)).isTrue();
    }

    @Test
    void recoveryNeverReactivatesTerminalJobsWithExpiredLeaseFields() {
        for (String terminal : List.of("SUCCEEDED", "FAILED", "CANCELLED")) {
            Fixture fixture = fixture();
            claimWorkerThenPublishAndExpire(fixture);
            jdbc.update("UPDATE media_processing_job SET status=? WHERE job_id=?", terminal, fixture.jobId());
            var before = outboxWithPublisherLease(fixture.eventId());

            deliveries.recover(fixture.jobId());

            assertThat(outboxWithPublisherLease(fixture.eventId())).isEqualTo(before);
        }
    }

    @Test
    void historicalNullLeaseTokenUsesNullSafeCooldownGenerationComparison() {
        Fixture fixture = fixture();
        claimWorkerThenPublishAndExpire(fixture);
        jdbc.update("UPDATE media_processing_job SET lease_token_hash=NULL WHERE job_id=?", fixture.jobId());

        Instant recoveryStartedAt = Instant.now();
        deliveries.recover(fixture.jobId());
        OutboxSnapshot firstRecovery = outbox(fixture.eventId());
        assertThat(firstRecovery.status()).isEqualTo("PENDING");
        assertThat(firstRecovery.processingRecoveryLeaseHash()).isNull();
        assertThat(firstRecovery.processingRecoveryNextAt()).isAfter(recoveryStartedAt.plusSeconds(119));

        MediaDeliveryTransactions.Delivery delivery = awaitRecoveredDelivery(fixture.eventId());
        assertThat(deliveries.published(delivery)).isTrue();
        deliveries.recover(fixture.jobId());
        assertThat(outboxStatus(fixture.eventId())).isEqualTo("PUBLISHED");

        jdbc.update("UPDATE media_request_outbox SET processing_recovery_next_at=? WHERE event_id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), fixture.eventId());
        deliveries.recover(fixture.jobId());
        assertThat(outboxStatus(fixture.eventId())).isEqualTo("PENDING");
        assertThat(outbox(fixture.eventId()).processingRecoveryLeaseHash()).isNull();

        delivery = awaitRecoveredDelivery(fixture.eventId());
        assertThat(deliveries.published(delivery)).isTrue();
        jdbc.update("UPDATE media_processing_job SET lease_token_hash=? WHERE job_id=?",
                "b".repeat(64), fixture.jobId());
        deliveries.recover(fixture.jobId());
        assertThat(outboxStatus(fixture.eventId())).isEqualTo("PENDING");
        assertThat(outbox(fixture.eventId()).processingRecoveryLeaseHash()).isEqualTo("b".repeat(64));
    }

    @Test
    void recoveryMarkerColumnsRemainNullForLegacyRowsUntilRecovery() {
        Fixture fixture = fixture();

        OutboxSnapshot initial = outbox(fixture.eventId());

        assertThat(initial.processingRecoveryLeaseHash()).isNull();
        assertThat(initial.processingRecoveryNextAt()).isNull();
    }

    @Test
    void concurrentRecoveryUsesRealTransactionsAndLeavesTheOutboxPending() throws Exception {
        Fixture fixture = fixture();
        claimWorkerThenPublishAndExpire(fixture);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Instant recoveryStartedAt = Instant.now();
        try {
            var first = workers.submit(() -> recoverConcurrently(fixture.jobId(), ready, start));
            var second = workers.submit(() -> recoverConcurrently(fixture.jobId(), ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }

        OutboxSnapshot recovered = outbox(fixture.eventId());
        assertThat(recovered.status()).isEqualTo("PENDING");
        assertThat(recovered.processingRecoveryLeaseHash()).isEqualTo(jobLeaseHash(fixture.jobId()));
        assertThat(recovered.processingRecoveryNextAt()).isAfter(recoveryStartedAt.plusSeconds(119));
    }

    private Fixture fixture() {
        String assetId = UUID.randomUUID().toString();
        String jobId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        String objectKey = "threads/recovery-baseline/" + assetId.replace("-", "") + ".jpg";
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO media_asset(asset_id, owner_id, provider, object_key, url, content_type,
                    byte_size, sha256, status, processing_status, created_at, updated_at)
                VALUES (?, 7001, 'local', ?, ?, 'image/jpeg', 4, ?, 'READY', 'PENDING', ?, ?)
                """, assetId, objectKey, "/uploads/" + objectKey, SHA,
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO media_processing_job(job_id, asset_id, job_version, preset,
                    processor_version, status, attempts, created_at, updated_at)
                VALUES (?, ?, 1, 'preview', '1', 'PENDING', 0, ?, ?)
                """, jobId, assetId, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO media_request_outbox(event_id, asset_id, job_id, job_version,
                    preset, processor_version, occurred_at, status, attempts, available_at)
                VALUES (?, ?, ?, 1, 'preview', '1', ?, 'PENDING', 0, ?)
                """, eventId, assetId, jobId, Timestamp.from(now),
                Timestamp.from(now.minusSeconds(1)));
        return new Fixture(assetId, jobId, eventId, new MediaRequestEnvelope(
                1, "MEDIA_PROCESSING_REQUESTED", eventId, assetId, jobId, 1,
                "preview", "1", now));
    }

    private MediaDeliveryTransactions.Delivery claimWorkerThenPublishAndExpire(Fixture fixture) {
        MediaDeliveryTransactions.Delivery delivery = deliveries.claim().orElseThrow();
        MediaClaimResponse workerLease = jobs.claim(fixture.jobId(), fixture.envelope());
        assertThat(workerLease.status()).isEqualTo(MediaJobService.STATUS_CLAIMED);
        assertThat(deliveries.published(delivery)).isTrue();
        expireJobLease(fixture.jobId());
        assertExpiredProcessingLease(fixture);
        assertThat(outboxStatus(fixture.eventId())).isEqualTo("PUBLISHED");
        return delivery;
    }

    private void claimWorkerThenFailAndExpire(Fixture fixture) {
        MediaDeliveryTransactions.Delivery delivery = deliveries.claim().orElseThrow();
        MediaClaimResponse workerLease = jobs.claim(fixture.jobId(), fixture.envelope());
        assertThat(workerLease.status()).isEqualTo(MediaJobService.STATUS_CLAIMED);
        deliveries.failed(delivery);
        expireJobLease(fixture.jobId());
        assertExpiredProcessingLease(fixture);
    }

    private void assertExpiredProcessingLease(Fixture fixture) {
        assertThat(jdbc.queryForObject("SELECT status FROM media_processing_job WHERE job_id=?",
                String.class, fixture.jobId())).isEqualTo(MediaJobService.STATUS_PROCESSING);
        assertThat(jdbc.queryForObject("SELECT lease_token_hash FROM media_processing_job WHERE job_id=?",
                String.class, fixture.jobId())).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT lease_until FROM media_processing_job WHERE job_id=?",
                Timestamp.class, fixture.jobId()).toInstant()).isBefore(Instant.now());
    }

    private void expireJobLease(String jobId) {
        jdbc.update("UPDATE media_processing_job SET lease_until=? WHERE job_id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), jobId);
    }

    private void recoverConcurrently(String jobId, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            deliveries.recover(jobId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("concurrent recovery worker interrupted", e);
        }
    }

    private MediaDeliveryTransactions.Delivery awaitRecoveredDelivery(String eventId) {
        for (int attempt = 0; attempt < 20; attempt++) {
            var delivery = deliveries.claim();
            if (delivery.isPresent()) {
                assertThat(delivery.get().eventId()).isEqualTo(eventId);
                return delivery.get();
            }
            try {
                TimeUnit.MILLISECONDS.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for recovered delivery", e);
            }
        }
        throw new AssertionError("recovered delivery was not claimable: " + outbox(eventId));
    }

    private String outboxStatus(String eventId) {
        return jdbc.queryForObject("SELECT status FROM media_request_outbox WHERE event_id=?",
                String.class, eventId);
    }

    private Map<String, Object> outboxWithPublisherLease(String eventId) {
        return jdbc.queryForMap("""
                SELECT status,attempts,available_at,lease_owner,lease_until,
                       processing_recovery_lease_hash,processing_recovery_next_at
                  FROM media_request_outbox WHERE event_id=?
                """, eventId);
    }

    private String jobLeaseHash(String jobId) {
        return jdbc.queryForObject("SELECT lease_token_hash FROM media_processing_job WHERE job_id=?",
                String.class, jobId);
    }

    private Instant jobUpdatedAt(String jobId) {
        return jdbc.queryForObject("SELECT updated_at FROM media_processing_job WHERE job_id=?",
                Timestamp.class, jobId).toInstant();
    }

    private OutboxSnapshot outbox(String eventId) {
        return jdbc.queryForObject("""
                SELECT status, attempts, available_at, last_error,
                       processing_recovery_lease_hash, processing_recovery_next_at
                FROM media_request_outbox WHERE event_id=?
                """, (rs, row) -> new OutboxSnapshot(
                rs.getString("status"), rs.getInt("attempts"),
                rs.getTimestamp("available_at").toInstant(), rs.getString("last_error"),
                rs.getString("processing_recovery_lease_hash"),
                rs.getTimestamp("processing_recovery_next_at") == null
                        ? null : rs.getTimestamp("processing_recovery_next_at").toInstant()), eventId);
    }

    private record Fixture(String assetId, String jobId, String eventId, MediaRequestEnvelope envelope) {
    }

    private record OutboxSnapshot(String status, int attempts, Instant availableAt, String lastError,
                                  String processingRecoveryLeaseHash, Instant processingRecoveryNextAt) {
    }
}
