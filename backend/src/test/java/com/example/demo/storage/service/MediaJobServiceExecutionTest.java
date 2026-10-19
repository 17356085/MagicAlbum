package com.example.demo.storage.service;

import com.example.demo.Demo1Application;
import com.example.demo.storage.dto.MediaClaimResponse;
import com.example.demo.storage.dto.MediaFailureRequest;
import com.example.demo.storage.dto.MediaRequestEnvelope;
import com.example.demo.storage.dto.MediaRenewRequest;
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
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = Demo1Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisabledInAotMode
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:media-execution;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "app.media.processing-lease-duration=10s",
        "app.media.processing-max-attempts=5"
})
class MediaJobServiceExecutionTest {

    private static final String OWNER = "media-owner";
    private static final String SHA = "a".repeat(64);

    @Autowired
    private MediaJobService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired private MediaDeliveryTransactions deliveries;
    @Autowired private MediaUploadRegistrationService registry;
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path objectsRoot;

    @BeforeEach
    void clear() {
        tx(() -> {
            jdbc.update("delete from media_request_outbox");
            jdbc.update("delete from media_processing_job");
            jdbc.update("delete from media_asset");
            return null;
        });
    }

    @Test
    void claimValidatesEnvelopeAndReturnsHashedLease() {
        Fixture fixture = fixture();
        MediaClaimResponse claim = service.claim(fixture.jobId(), fixture.envelope());

        assertThat(claim.status()).isEqualTo(MediaJobService.STATUS_CLAIMED);
        assertThat(claim.leaseToken()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(claim.inputSha256()).isEqualTo(SHA);
        assertThat(jdbc.queryForObject("select lease_token_hash from media_processing_job where job_id=?",
                String.class, fixture.jobId())).isNotEqualTo(claim.leaseToken());
        assertThat(jdbc.queryForObject("select attempts from media_processing_job where job_id=?",
                Integer.class, fixture.jobId())).isEqualTo(1);

        MediaRequestEnvelope invalid = new MediaRequestEnvelope(
                2, fixture.envelope().eventType(), fixture.envelope().eventId(), fixture.assetId(),
                fixture.jobId(), 1, "preview", "1", fixture.envelope().occurredAt());
        assertThatThrownBy(() -> service.claim(fixture.jobId(), invalid))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    void concurrentClaimHasSingleOwnerAndBusyResponse() throws Exception {
        Fixture fixture = fixture();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<Object> first = executor.submit(() -> claimAfterBarrier(fixture, barrier));
            Future<Object> second = executor.submit(() -> claimAfterBarrier(fixture, barrier));
            Object one = first.get(10, TimeUnit.SECONDS);
            Object two = second.get(10, TimeUnit.SECONDS);
            assertThat(one instanceof MediaClaimResponse ^ two instanceof MediaClaimResponse).isTrue();
            Object failure = one instanceof ResponseStatusException ? one : two;
            assertThat(((ResponseStatusException) failure).getStatusCode().value()).isEqualTo(409);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredLeaseCanBeReclaimedAndOldTokenCannotRenew() {
        Fixture fixture = fixture();
        MediaClaimResponse first = service.claim(fixture.jobId(), fixture.envelope());
        jdbc.update("update media_processing_job set lease_until=? where job_id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), fixture.jobId());

        MediaClaimResponse second = service.claim(fixture.jobId(), fixture.envelope());
        assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(jdbc.queryForObject("select attempts from media_processing_job where job_id=?",
                Integer.class, fixture.jobId())).isEqualTo(2);
        assertThatThrownBy(() -> service.renew(fixture.jobId(), new MediaRenewRequest(1, first.leaseToken())))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(409);
        assertThat(service.renew(fixture.jobId(), new MediaRenewRequest(1, second.leaseToken())).leaseUntil())
                .isAfter(Instant.now());
    }

    @Test
    void failureIsFixedCodeAndSameLeaseRetryIsIdempotent() {
        Fixture fixture = fixture();
        MediaClaimResponse claim = service.claim(fixture.jobId(), fixture.envelope());
        MediaFailureRequest request = new MediaFailureRequest(1, claim.leaseToken(), "MALFORMED_IMAGE");
        assertThat(service.fail(fixture.jobId(), request).status()).isEqualTo(MediaJobService.STATUS_FAILED);
        assertThat(service.fail(fixture.jobId(), request).status()).isEqualTo(MediaJobService.STATUS_FAILED);
        assertThatThrownBy(() -> service.fail(fixture.jobId(),
                new MediaFailureRequest(1, claim.leaseToken(), "TIMEOUT")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(409);
        assertThat(jdbc.queryForObject("select processing_status from media_asset where asset_id=?",
                String.class, fixture.assetId())).isEqualTo(MediaJobService.PROCESSING_FAILED);
    }

    @Test
    void ownerCancelInvalidatesCurrentLease() {
        Fixture fixture = fixture();
        MediaClaimResponse claim = service.claim(fixture.jobId(), fixture.envelope());
        assertThat(service.cancelOwned(fixture.assetId(), 7L).status()).isEqualTo(MediaJobService.STATUS_CANCELLED);
        assertThatThrownBy(() -> service.renew(fixture.jobId(), new MediaRenewRequest(1, claim.leaseToken())))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(409);
        assertThatThrownBy(() -> service.cancelOwned(fixture.assetId(), 8L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void retryCreatesNewVersionAndLatestOwnerStateRejectsStaleCallbacks() {
        Fixture fixture = fixture();
        MediaClaimResponse old = service.claim(fixture.jobId(), fixture.envelope());
        service.cancelOwned(fixture.assetId(), 7L);
        service.retryOwned(fixture.assetId(), 7L);
        var state = registry.findOwned(7L, fixture.assetId());
        assertThat(state.jobId()).isNotEqualTo(fixture.jobId());
        assertThat(state.processingStatus()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select job_version from media_processing_job where job_id=?", Integer.class, state.jobId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from media_request_outbox where asset_id=?", Integer.class, fixture.assetId())).isEqualTo(2);
        assertThat(service.claim(fixture.jobId(), fixture.envelope()).status()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> service.fail(fixture.jobId(), new MediaFailureRequest(1, old.leaseToken(), "TIMEOUT")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.retryOwned(fixture.assetId(), 7L)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.retryOwned(fixture.assetId(), 8L))
                .isInstanceOf(ResponseStatusException.class).extracting(error -> ((ResponseStatusException) error).getStatusCode().value()).isEqualTo(404);
        assertThat(registry.findOwned(7L, fixture.assetId()).processingStatus()).isEqualTo("PENDING");
    }

    @Test
    void concurrentOwnerRetriesCreateOnlyOneNewVersion() throws Exception {
        Fixture fixture = fixture();
        service.cancelOwned(fixture.assetId(), 7L);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> retry = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try { service.retryOwned(fixture.assetId(), 7L); return true; }
                catch (ResponseStatusException conflict) { assertThat(conflict.getStatusCode().value()).isEqualTo(409); return false; }
            };
            var first = executor.submit(retry);
            var second = executor.submit(retry);
            assertThat(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("select count(*) from media_processing_job where asset_id=?", Integer.class, fixture.assetId())).isEqualTo(2);
    }

    @Test
    void publisherLeaseRejectsOldOwnerAndRecoveryReusesOriginalEvent() {
        Fixture fixture = fixture();
        var first = deliveries.claim().orElseThrow();
        assertThat(first.eventId()).isEqualTo(fixture.envelope().eventId());
        assertThat(deliveries.claim()).isEmpty();
        jdbc.update("update media_request_outbox set lease_until=? where event_id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)), first.eventId());
        var second = deliveries.claim().orElseThrow();
        assertThat(second.owner()).isNotEqualTo(first.owner());
        assertThat(deliveries.published(first)).isFalse();
        assertThat(deliveries.published(second)).isTrue();
        jdbc.update("update media_processing_job set updated_at=? where job_id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(121)), fixture.jobId());
        deliveries.recover(fixture.jobId());
        assertThat(awaitRecoveredDelivery().eventId()).isEqualTo(first.eventId());
    }

    @Test
    void recoveryRepublishesExpiredLeaseWithoutWaitingForPendingThreshold() {
        Fixture fixture = fixture();
        service.claim(fixture.jobId(), fixture.envelope());
        var delivery = deliveries.claim().orElseThrow();
        assertThat(deliveries.published(delivery)).isTrue();
        jdbc.update("update media_processing_job set lease_until=?,updated_at=? where job_id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), java.sql.Timestamp.from(Instant.now()), fixture.jobId());

        maintenance().recoverJobs();

        assertThat(awaitRecoveredDelivery().eventId()).isEqualTo(delivery.eventId());
    }

    @Test
    void recoveryDoesNotRepublishLiveLeaseEvenWithOldUpdateTimestamp() {
        Fixture fixture = fixture();
        service.claim(fixture.jobId(), fixture.envelope());
        var delivery = deliveries.claim().orElseThrow();
        assertThat(deliveries.published(delivery)).isTrue();
        jdbc.update("update media_processing_job set lease_until=?,updated_at=? where job_id=?",
                java.sql.Timestamp.from(Instant.now().plusSeconds(60)), java.sql.Timestamp.from(Instant.now().minusSeconds(121)), fixture.jobId());

        maintenance().recoverJobs();
        deliveries.recover(fixture.jobId());

        assertThat(deliveries.claim()).isEmpty();
    }

    @Test
    void recoveryKeepsPendingGracePeriodAndThenReusesOriginalEvent() {
        Fixture fixture = fixture();
        var delivery = deliveries.claim().orElseThrow();
        assertThat(deliveries.published(delivery)).isTrue();
        maintenance().recoverJobs();
        deliveries.recover(fixture.jobId());
        assertThat(deliveries.claim()).isEmpty();

        jdbc.update("update media_processing_job set updated_at=? where job_id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(121)), fixture.jobId());
        maintenance().recoverJobs();

        assertThat(awaitRecoveredDelivery().eventId()).isEqualTo(delivery.eventId());
    }

    private MediaDeliveryTransactions.Delivery awaitRecoveredDelivery() {
        // Recovery schedules available_at="now". Assert scheduler-visible readiness
        // without requiring the next wall-clock read to exceed DB timestamp rounding.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            var delivery = deliveries.claim();
            if (delivery.isPresent()) return delivery.orElseThrow();
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("Recovered outbox did not become publishable: "
                + jdbc.queryForList("select status,available_at from media_request_outbox"));
    }

    private MediaMaintenance maintenance() {
        return new MediaMaintenance(jdbc, deliveries, registry,
                org.mockito.Mockito.mock(MediaObjectStore.class), org.mockito.Mockito.mock(MediaMaintenanceTransactions.class),
                java.time.Duration.ofHours(24), true);
    }

    @Test
    void cleanupKeepsActiveCandidateAndAcceptedOutputButDeletesExpiredOrphan() throws Exception {
        Fixture fixture = fixture();
        var objects = new MediaObjectStore(org.mockito.Mockito.mock(com.example.demo.storage.service.provider.s3.S3StorageService.class), objectsRoot.toString());
        var maintenance = new MediaMaintenanceTransactions(jdbc, objects);
        var stored = objects.planDerived("local", fixture.assetId(), 1, "preview", "1", SHA, "image/png");
        byte[] bytes = {1, 2, 3};
        objects.write(stored, bytes);
        var candidate = new MediaJobService.CandidateMetadata(stored.objectKey(), stored.url(), SHA, "image/png", bytes.length, 1, 1);
        var lease = service.claim(fixture.jobId(), fixture.envelope());
        service.reserveResult(fixture.jobId(), 1, lease.leaseToken(), candidate);
        assertThat(tx(() -> maintenance.deleteUnreferenced("local", stored.objectKey()))).isFalse();
        assertThat(java.nio.file.Files.readAllBytes(objectsRoot.resolve(stored.objectKey()))).isEqualTo(bytes);
        jdbc.update("update media_processing_job set lease_until=? where job_id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)), fixture.jobId());
        assertThat(tx(() -> maintenance.deleteUnreferenced("local", stored.objectKey()))).isTrue();
        assertThat(java.nio.file.Files.exists(objectsRoot.resolve(stored.objectKey()))).isFalse();
        var current = service.claim(fixture.jobId(), fixture.envelope());
        service.reserveResult(fixture.jobId(), 1, current.leaseToken(), candidate);
        objects.write(stored, bytes);
        service.acceptResult(fixture.jobId(), 1, current.leaseToken(), candidate);
        assertThat(tx(() -> maintenance.deleteUnreferenced("local", stored.objectKey()))).isFalse();
        assertThatThrownBy(() -> maintenance.deleteUnreferenced("local", "source.jpg")).isInstanceOf(IllegalArgumentException.class);
        assertThat(java.nio.file.Files.exists(objectsRoot.resolve(stored.objectKey()))).isTrue();
    }

    private Object claimAfterBarrier(Fixture fixture, CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
            return service.claim(fixture.jobId(), fixture.envelope());
        } catch (ResponseStatusException exception) {
            return exception;
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private Fixture fixture() {
        String assetId = UUID.randomUUID().toString();
        String jobId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        tx(() -> {
            Instant now = Instant.now();
            jdbc.update("""
                    insert into media_asset(asset_id, owner_id, provider, object_key, url, content_type,
                        byte_size, sha256, status, processing_status, created_at, updated_at)
                    values (?, ?, 'local', 'source.jpg', '/uploads/source.jpg', 'image/jpeg',
                        4, ?, 'READY', 'PENDING', ?, ?)
                    """, assetId, 7L, SHA, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
            jdbc.update("""
                    insert into media_processing_job(job_id, asset_id, job_version, preset,
                        processor_version, status, attempts, created_at, updated_at)
                    values (?, ?, 1, 'preview', '1', 'PENDING', 0, ?, ?)
                    """, jobId, assetId, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
            jdbc.update("""
                    insert into media_request_outbox(event_id, asset_id, job_id, job_version,
                        preset, processor_version, occurred_at, status, attempts, available_at)
                    values (?, ?, ?, 1, 'preview', '1', ?, 'PENDING', 0, ?)
                    """, eventId, assetId, jobId, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now.minusSeconds(60)));
            return null;
        });
        return new Fixture(assetId, jobId, new MediaRequestEnvelope(
                1, "MEDIA_PROCESSING_REQUESTED", eventId, assetId, jobId, 1,
                "preview", "1", Instant.now()));
    }

    private <T> T tx(java.util.function.Supplier<T> work) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> work.get());
    }

    private record Fixture(String assetId, String jobId, MediaRequestEnvelope envelope) {
    }
}
