package com.example.demo.storage.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicit-only MySQL 8 acceptance for media recovery lease generations.
 *
 * <p>The class intentionally does not match the default Surefire include
 * patterns. Run it explicitly with
 * {@code mvn -Dtest=MediaRecoveryMySqlAcceptance test} and the five
 * {@code MEDIA_RECOVERY_MYSQL_*} environment variables documented below.</p>
 *
 * <p>This verifies database transactions and durable recovery state only. It
 * does not prove that RabbitMQ or another broker accepted a message, and the
 * two-thread check covers one row-lock race rather than every interleaving.
 * No real 120-second recovery grace period is waited for.</p>
 */
class MediaRecoveryMySqlAcceptance {

    private static final String URL_ENV = "MEDIA_RECOVERY_MYSQL_URL";
    private static final String USER_ENV = "MEDIA_RECOVERY_MYSQL_USER";
    private static final String PASSWORD_ENV = "MEDIA_RECOVERY_MYSQL_PASSWORD";
    private static final String DATABASE_ENV = "MEDIA_RECOVERY_MYSQL_EXPECTED_DATABASE";
    private static final String MARKER_ENV = "MEDIA_RECOVERY_MYSQL_MARKER";
    private static final Pattern URL_PATTERN = Pattern.compile(
            "^jdbc:mysql://127\\.0\\.0\\.1:(?<port>[0-9]{1,5})/"
                    + "(?<database>[A-Za-z0-9_]+)(?:\\?[^\\s#]*)?$");
    private static final Pattern DATABASE_PATTERN = Pattern.compile(
            "(?:ba_content_upgrade_[0-9a-f]{16,32}|ba_media_recovery_[0-9a-f]{16,32})");
    private static final String SHA256 = "a".repeat(64);
    private static final String PRESET = "preview";
    private static final String PROCESSOR_VERSION = "codex-mysql-acceptance";

    @Test
    void recoversExpiredLeasesWithStrictTokenGenerationAndTransactions() throws Exception {
        Config config = Config.fromEnvironment();
        DataSource dataSource = dataSource(config);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // This is deliberately the first database operation that can write:
        // every check above and below is read-only and must pass first.
        verifyIsolatedMySql(jdbc, config);

        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        MediaDeliveryTransactions deliveries = new MediaDeliveryTransactions(jdbc);
        Instant oldUpdatedAt = Instant.parse("2001-01-01T00:00:00Z");

        Fixture first = insertFixture(jdbc, transaction, "PUBLISHED");
        setJobUpdatedAt(jdbc, transaction, first.jobId(), oldUpdatedAt);
        recover(transaction, deliveries, first.jobId());
        assertThat(outbox(jdbc, first.eventId()).status())
                .as("the first expired lease must make its published outbox due")
                .isEqualTo("PENDING");
        ProcessingSnapshot firstJob = processing(jdbc, first.jobId());
        RecoverySnapshot firstRecovery = recovery(jdbc, first.eventId());
        assertRecoveryMarker(firstJob, firstRecovery, first.leaseTokenHash());
        assertThat(jobUpdatedAt(jdbc, first.jobId())).isAfter(oldUpdatedAt);

        publishWithRealDatabaseTransactions(jdbc, transaction, deliveries, first);
        setJobUpdatedAt(jdbc, transaction, first.jobId(), oldUpdatedAt);
        recover(transaction, deliveries, first.jobId());
        assertThat(outbox(jdbc, first.eventId()).status())
                .as("the same lease token must be suppressed while next_at is in the future")
                .isEqualTo("PUBLISHED");
        ProcessingSnapshot suppressedJob = processing(jdbc, first.jobId());
        RecoverySnapshot suppressed = recovery(jdbc, first.eventId());
        assertRecoveryMarker(suppressedJob, suppressed, first.leaseTokenHash());
        assertThat(suppressed.nextAt())
                .as("same-token suppression must retain the scheduled next recovery")
                .isEqualTo(firstRecovery.nextAt());
        assertThat(jobUpdatedAt(jdbc, first.jobId()))
                .as("same-token suppression must not update the job timestamp")
                .isEqualTo(oldUpdatedAt);

        setRecoveryNextAt(jdbc, transaction, first.eventId(), Instant.now().minusSeconds(1));
        recover(transaction, deliveries, first.jobId());
        assertThat(outbox(jdbc, first.eventId()).status())
                .as("an isolated next_at moved into the past must permit recovery")
                .isEqualTo("PENDING");
        ProcessingSnapshot afterManualDueJob = processing(jdbc, first.jobId());
        RecoverySnapshot afterManualDue = recovery(jdbc, first.eventId());
        assertRecoveryMarker(afterManualDueJob, afterManualDue, first.leaseTokenHash());
        assertThat(afterManualDue.nextAt()).isAfter(Instant.now());

        publishWithRealDatabaseTransactions(jdbc, transaction, deliveries, first);
        String newLeaseToken = tokenHash();
        updateExpiredLease(jdbc, transaction, first.jobId(), newLeaseToken);
        recover(transaction, deliveries, first.jobId());
        assertThat(outbox(jdbc, first.eventId()).status())
                .as("a different expired lease token must recover immediately")
                .isEqualTo("PENDING");
        ProcessingSnapshot newGenerationJob = processing(jdbc, first.jobId());
        RecoverySnapshot newGeneration = recovery(jdbc, first.eventId());
        assertRecoveryMarker(newGenerationJob, newGeneration, newLeaseToken);
        publishWithRealDatabaseTransactions(jdbc, transaction, deliveries, first);

        Fixture nullToken = insertFixture(jdbc, transaction, "PUBLISHED");
        transaction.executeWithoutResult(status -> jdbc.update(
                "UPDATE media_processing_job SET lease_token_hash=NULL WHERE job_id=?", nullToken.jobId()));
        recover(transaction, deliveries, nullToken.jobId());
        assertThat(outbox(jdbc, nullToken.eventId()).status()).isEqualTo("PENDING");
        RecoverySnapshot nullGeneration = recovery(jdbc, nullToken.eventId());
        assertThat(nullGeneration.leaseHash()).isNull();
        assertThat(nullGeneration.nextAt()).isAfter(Instant.now());
        publishWithRealDatabaseTransactions(jdbc, transaction, deliveries, nullToken);
        recover(transaction, deliveries, nullToken.jobId());
        assertThat(outbox(jdbc, nullToken.eventId()).status()).isEqualTo("PUBLISHED");
        assertThat(recovery(jdbc, nullToken.eventId())).isEqualTo(nullGeneration);
        updateExpiredLease(jdbc, transaction, nullToken.jobId(), tokenHash());
        recover(transaction, deliveries, nullToken.jobId());
        assertThat(outbox(jdbc, nullToken.eventId()).status()).isEqualTo("PENDING");
        publishWithRealDatabaseTransactions(jdbc, transaction, deliveries, nullToken);

        // Exercise the real failed transaction before checking that recovery
        // does not reset retry backoff state on a FAILED outbox row.
        Fixture failed = insertFixture(jdbc, transaction, "PENDING");
        MediaDeliveryTransactions.Delivery failedDelivery = claim(transaction, deliveries);
        assertThat(failedDelivery.eventId()).isEqualTo(failed.eventId());
        fail(transaction, deliveries, failedDelivery);
        OutboxSnapshot failedBeforeRecovery = outbox(jdbc, failed.eventId());
        RecoverySnapshot failedMarkerBeforeRecovery = recovery(jdbc, failed.eventId());
        assertThat(failedBeforeRecovery.status()).isEqualTo("FAILED");
        setJobUpdatedAt(jdbc, transaction, failed.jobId(), oldUpdatedAt);
        recover(transaction, deliveries, failed.jobId());
        OutboxSnapshot failedAfterRecovery = outbox(jdbc, failed.eventId());
        assertThat(failedAfterRecovery.status()).isEqualTo("FAILED");
        assertThat(failedAfterRecovery.attempts())
                .as("recovery must not reset FAILED attempts")
                .isEqualTo(failedBeforeRecovery.attempts());
        assertThat(failedAfterRecovery.availableAt())
                .as("recovery must not change FAILED available_at")
                .isEqualTo(failedBeforeRecovery.availableAt());
        assertThat(recovery(jdbc, failed.eventId()))
                .as("recovery must not change a FAILED outbox marker")
                .isEqualTo(failedMarkerBeforeRecovery);
        assertThat(jobUpdatedAt(jdbc, failed.jobId())).isEqualTo(oldUpdatedAt);

        transaction.executeWithoutResult(status -> jdbc.update(
                "UPDATE media_processing_job SET status='PENDING',updated_at=? WHERE job_id=?",
                Timestamp.from(oldUpdatedAt), failed.jobId()));
        recover(transaction, deliveries, failed.jobId());
        Instant deferredAt = jobUpdatedAt(jdbc, failed.jobId());
        assertThat(deferredAt).isAfter(oldUpdatedAt);
        assertThat(outbox(jdbc, failed.eventId()).status()).isEqualTo("FAILED");
        recover(transaction, deliveries, failed.jobId());
        assertThat(jobUpdatedAt(jdbc, failed.jobId())).isEqualTo(deferredAt);

        Fixture concurrent = insertFixture(jdbc, transaction, "PUBLISHED");
        concurrentRecoveries(transactionManager, deliveries, concurrent.jobId());
        assertThat(outbox(jdbc, concurrent.eventId()).status())
                .as("two concurrent recoveries must leave one durable due row")
                .isEqualTo("PENDING");
        ProcessingSnapshot concurrentJob = processing(jdbc, concurrent.jobId());
        RecoverySnapshot concurrentState = recovery(jdbc, concurrent.eventId());
        assertRecoveryMarker(concurrentJob, concurrentState, concurrent.leaseTokenHash());
    }

    private static DataSource dataSource(Config config) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(config.url());
        dataSource.setUsername(config.user());
        dataSource.setPassword(config.password());
        return dataSource;
    }

    private static void verifyIsolatedMySql(JdbcTemplate jdbc, Config config) {
        String product = jdbc.execute((ConnectionCallback<String>)
                connection -> connection.getMetaData().getDatabaseProductName());
        assertCondition("MySQL".equals(product), "the configured JDBC product is not MySQL");

        String selectedDatabase = jdbc.queryForObject("SELECT DATABASE()", String.class);
        assertCondition(config.expectedDatabase().equals(selectedDatabase),
                "SELECT DATABASE() does not match the expected isolated database");

        List<String> markers = jdbc.query("SELECT marker FROM codex_media_recovery_guard",
                (resultSet, rowNum) -> resultSet.getString(1));
        assertCondition(markers.size() == 1, "the recovery guard must contain exactly one marker");
        assertCondition(new HashSet<>(markers).size() == 1, "the recovery guard marker must be unique");
        assertCondition(config.marker().equals(markers.get(0)),
                "the recovery guard marker does not match the configured marker");
    }

    private static Fixture insertFixture(JdbcTemplate jdbc, TransactionTemplate transaction,
                                         String outboxStatus) {
        assertCondition("PENDING".equals(outboxStatus) || "PUBLISHED".equals(outboxStatus),
                "fixture status must be a supported initial status");
        Fixture fixture = Fixture.create(outboxStatus);
        transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO media_asset(asset_id, owner_id, provider, object_key, url, content_type,
                        byte_size, sha256, status, processing_status, created_at, updated_at)
                    VALUES (?, 7001, 'local', ?, ?, 'image/jpeg', 4, ?, 'READY', 'PROCESSING', ?, ?)
                    """, fixture.assetId(), fixture.objectKey(), "/uploads/" + fixture.objectKey(),
                    SHA256, Timestamp.from(fixture.now()), Timestamp.from(fixture.now()));
            jdbc.update("""
                    INSERT INTO media_processing_job(job_id, asset_id, job_version, preset,
                        processor_version, status, attempts, created_at, updated_at,
                        lease_token_hash, lease_until)
                    VALUES (?, ?, 1, ?, ?, 'PROCESSING', 1, ?, ?, ?, ?)
                    """, fixture.jobId(), fixture.assetId(), PRESET, PROCESSOR_VERSION,
                    Timestamp.from(fixture.now()), Timestamp.from(fixture.now()),
                    fixture.leaseTokenHash(), Timestamp.from(fixture.expiredAt()));
            jdbc.update("""
                    INSERT INTO media_request_outbox(event_id, asset_id, job_id, job_version,
                        preset, processor_version, occurred_at, status, attempts, available_at,
                        lease_owner, lease_until, last_error)
                    VALUES (?, ?, ?, 1, ?, ?, ?, ?, 0, ?, NULL, NULL, NULL)
                    """, fixture.eventId(), fixture.assetId(), fixture.jobId(), PRESET,
                    PROCESSOR_VERSION, Timestamp.from(fixture.now()), outboxStatus,
                    Timestamp.from(fixture.now().minusSeconds(1)));
        });
        return fixture;
    }

    private static void recover(TransactionTemplate transaction, MediaDeliveryTransactions deliveries,
                                String jobId) {
        transaction.executeWithoutResult(status -> deliveries.recover(jobId));
    }

    private static MediaDeliveryTransactions.Delivery claim(TransactionTemplate transaction,
                                                             MediaDeliveryTransactions deliveries) {
        for (int attempt = 0; attempt < 20; attempt++) {
            var delivery = transaction.execute(status -> deliveries.claim());
            if (delivery != null && delivery.isPresent()) return delivery.get();
            try {
                TimeUnit.MILLISECONDS.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for the isolated delivery", e);
            }
        }
        throw new AssertionError("the isolated outbox row was not claimable");
    }

    private static void publishWithRealDatabaseTransactions(JdbcTemplate jdbc, TransactionTemplate transaction,
                                                             MediaDeliveryTransactions deliveries, Fixture fixture) {
        MediaDeliveryTransactions.Delivery delivery = claim(transaction, deliveries);
        assertThat(delivery.eventId()).isEqualTo(fixture.eventId());
        Boolean published = transaction.execute(status -> deliveries.published(delivery));
        assertCondition(Boolean.TRUE.equals(published), "the real published transaction did not update the row");
        assertThat(outbox(jdbc, fixture.eventId()).status()).isEqualTo("PUBLISHED");
    }

    private static void fail(TransactionTemplate transaction, MediaDeliveryTransactions deliveries,
                             MediaDeliveryTransactions.Delivery delivery) {
        transaction.executeWithoutResult(status -> deliveries.failed(delivery));
    }

    private static void setRecoveryNextAt(JdbcTemplate jdbc, TransactionTemplate transaction, String eventId,
                                          Instant nextAt) {
        transaction.executeWithoutResult(status -> {
            int updated = jdbc.update("UPDATE media_request_outbox SET processing_recovery_next_at=? WHERE event_id=?",
                    Timestamp.from(nextAt), eventId);
            assertCondition(updated == 1, "the isolated recovery row was not updated");
        });
    }

    private static void setJobUpdatedAt(JdbcTemplate jdbc, TransactionTemplate transaction, String jobId,
                                        Instant updatedAt) {
        transaction.executeWithoutResult(status -> {
            int updated = jdbc.update("UPDATE media_processing_job SET updated_at=? WHERE job_id=?",
                    Timestamp.from(updatedAt), jobId);
            assertCondition(updated == 1, "the isolated processing job was not updated");
        });
    }

    private static Instant jobUpdatedAt(JdbcTemplate jdbc, String jobId) {
        return jdbc.queryForObject("SELECT updated_at FROM media_processing_job WHERE job_id=?",
                Timestamp.class, jobId).toInstant();
    }

    private static void updateExpiredLease(JdbcTemplate jdbc, TransactionTemplate transaction, String jobId,
                                           String leaseTokenHash) {
        transaction.executeWithoutResult(status -> {
            int updated = jdbc.update("""
                    UPDATE media_processing_job
                    SET lease_token_hash=?, lease_until=?, updated_at=?
                    WHERE job_id=?
                    """, leaseTokenHash, Timestamp.from(Instant.now().minusSeconds(1)),
                    Timestamp.from(Instant.now()), jobId);
            assertCondition(updated == 1, "the isolated processing lease was not replaced");
        });
    }

    private static OutboxSnapshot outbox(JdbcTemplate jdbc, String eventId) {
        return jdbc.queryForObject("""
                SELECT status, attempts, available_at, last_error
                FROM media_request_outbox WHERE event_id=?
                """, (resultSet, rowNum) -> new OutboxSnapshot(
                resultSet.getString("status"), resultSet.getInt("attempts"),
                resultSet.getTimestamp("available_at").toInstant(), resultSet.getString("last_error")), eventId);
    }

    private static ProcessingSnapshot processing(JdbcTemplate jdbc, String jobId) {
        return jdbc.queryForObject("""
                SELECT status, lease_token_hash, lease_until
                FROM media_processing_job WHERE job_id=?
                """, (resultSet, rowNum) -> new ProcessingSnapshot(
                resultSet.getString("status"), resultSet.getString("lease_token_hash"),
                resultSet.getTimestamp("lease_until").toInstant()), jobId);
    }

    private static RecoverySnapshot recovery(JdbcTemplate jdbc, String eventId) {
        return jdbc.queryForObject("""
                SELECT processing_recovery_lease_hash, processing_recovery_next_at
                FROM media_request_outbox WHERE event_id=?
                """, (resultSet, rowNum) -> new RecoverySnapshot(
                resultSet.getString("processing_recovery_lease_hash"),
                resultSet.getTimestamp("processing_recovery_next_at") == null
                        ? null : resultSet.getTimestamp("processing_recovery_next_at").toInstant()), eventId);
    }

    private static void assertRecoveryMarker(ProcessingSnapshot job, RecoverySnapshot recovery,
                                             String expectedToken) {
        assertThat(job.status()).isEqualTo("PROCESSING");
        assertThat(job.leaseTokenHash()).isEqualTo(expectedToken);
        assertThat(job.leaseUntil()).isBefore(Instant.now());
        assertCondition(expectedToken.equals(recovery.leaseHash()),
                "the processing recovery marker does not match the isolated lease token");
        assertThat(recovery.nextAt()).isAfter(Instant.now());
    }

    private static void concurrentRecoveries(DataSourceTransactionManager transactionManager,
                                             MediaDeliveryTransactions deliveries, String jobId) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<Throwable> first = executor.submit(() -> concurrentRecoveryWorker(
                    transactionManager, deliveries, jobId, barrier));
            Future<Throwable> second = executor.submit(() -> concurrentRecoveryWorker(
                    transactionManager, deliveries, jobId, barrier));
            Throwable firstFailure = first.get(30, TimeUnit.SECONDS);
            Throwable secondFailure = second.get(30, TimeUnit.SECONDS);
            assertThat(firstFailure).as("first concurrent recovery transaction").isNull();
            assertThat(secondFailure).as("second concurrent recovery transaction").isNull();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private static Throwable concurrentRecoveryWorker(DataSourceTransactionManager transactionManager,
                                                      MediaDeliveryTransactions deliveries, String jobId,
                                                      CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            TransactionTemplate ownTransaction = new TransactionTemplate(transactionManager);
            ownTransaction.executeWithoutResult(status -> deliveries.recover(jobId));
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static String tokenHash() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    private static void assertCondition(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Fixture(String assetId, String jobId, String eventId, String objectKey,
                           String leaseTokenHash, Instant now, Instant expiredAt, String outboxStatus) {
        private static Fixture create(String outboxStatus) {
            String assetId = UUID.randomUUID().toString();
            String jobId = UUID.randomUUID().toString();
            String eventId = UUID.randomUUID().toString();
            Instant now = Instant.now();
            return new Fixture(assetId, jobId, eventId,
                    "codex-media-recovery/" + assetId.replace("-", "") + ".jpg",
                    tokenHash(), now, now.minusSeconds(1), outboxStatus);
        }
    }

    private record OutboxSnapshot(String status, int attempts, Instant availableAt, String lastError) {
    }

    private record ProcessingSnapshot(String status, String leaseTokenHash, Instant leaseUntil) {
    }

    private record RecoverySnapshot(String leaseHash, Instant nextAt) {
    }

    private record Config(String url, String user, String password, String expectedDatabase, String marker) {
        private static Config fromEnvironment() {
            String url = required(URL_ENV, true);
            String user = required(USER_ENV, true);
            String password = required(PASSWORD_ENV, false);
            String expectedDatabase = required(DATABASE_ENV, true);
            String marker = required(MARKER_ENV, true);

            Matcher matcher = URL_PATTERN.matcher(url);
            assertCondition(matcher.matches(), "the MySQL URL is outside the localhost-only acceptance boundary");
            int port;
            try {
                port = Integer.parseInt(matcher.group("port"));
            } catch (RuntimeException invalidPort) {
                throw new IllegalStateException("the MySQL URL port is invalid");
            }
            assertCondition(port >= 1024 && port <= 65535,
                    "the MySQL URL port must be an unprivileged port");
            assertCondition(port != 35174 && port != 3306 && port != 3307,
                    "the MySQL URL port is reserved by the acceptance boundary");

            String urlDatabase = matcher.group("database");
            assertCondition(DATABASE_PATTERN.matcher(urlDatabase).matches(),
                    "the MySQL URL database is outside the acceptance naming boundary");
            assertCondition(DATABASE_PATTERN.matcher(expectedDatabase).matches(),
                    "the expected database is outside the acceptance naming boundary");
            assertCondition(urlDatabase.equals(expectedDatabase),
                    "the URL database and expected database do not match");
            return new Config(url, user, password, expectedDatabase, marker);
        }

        private static String required(String name, boolean nonBlank) {
            String value = System.getenv(name);
            assertCondition(value != null, "required environment configuration is missing: " + name);
            if (nonBlank) {
                assertCondition(!value.isBlank(), "required environment configuration is blank: " + name);
            }
            return value;
        }
    }
}
