package com.example.demo.notifications.outbox;

import com.example.demo.user.service.UserSettingsService;
import org.assertj.core.api.SoftAssertions;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real-database concurrency reproduction for a claim candidate read that outlives
 * the transaction which made the row in-flight.
 */
@SpringBootTest(classes = com.example.demo.Demo1Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisabledInAotMode
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:notification-outbox-claim-snapshot-20261026;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.jpa.show-sql=false",
        "app.notifications.outbox.scheduling-enabled=false",
        "app.notifications.outbox.lease-duration=30s",
        "app.notifications.outbox.batch-size=1",
        "app.notifications.outbox.max-attempts=2",
        "app.notifications.outbox.backoff-base=1ms",
        "app.notifications.outbox.retention=1d"
})
class NotificationOutboxClaimSnapshotTest {
    private static final Instant T0 = Instant.parse("2026-10-26T12:00:00Z");
    private static final Instant T1 = T0.plusSeconds(31);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration BACKOFF = Duration.ofMillis(1);
    private static final Duration RETENTION = Duration.ofDays(1);
    private static final long WAIT_SECONDS = 10;
    private static final String A_OWNER = "snapshot-owner-a";
    private static final String B_OWNER = "snapshot-owner-b";
    private static final String C_OWNER = "snapshot-owner-c";
    private static final String MYSQL_URL_ENV = "OUTBOX_CLAIM_MYSQL_URL";
    private static final String MYSQL_USER_ENV = "OUTBOX_CLAIM_MYSQL_USER";
    private static final String MYSQL_PASSWORD_ENV = "OUTBOX_CLAIM_MYSQL_PASSWORD";
    private static final String MYSQL_SCHEMA_PREFIX = "notification_claim_acceptance_";
    private static final int MYSQL_PORT = 33183;

    @Autowired
    private NotificationOutboxRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private UserSettingsService userSettingsService;

    @DynamicPropertySource
    static void optionalMySqlFixture(DynamicPropertyRegistry registry) {
        String url = environment(MYSQL_URL_ENV);
        if (url.isBlank()) {
            return;
        }
        validateMySqlFixture(url);
        String user = environment(MYSQL_USER_ENV);
        String password = environment(MYSQL_PASSWORD_ENV);
        if (user.isBlank() || password.isBlank()) {
            throw new IllegalStateException(MYSQL_USER_ENV + " and " + MYSQL_PASSWORD_ENV
                    + " are required when " + MYSQL_URL_ENV + " is set");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("spring.datasource.hikari.transaction-isolation", () -> "TRANSACTION_REPEATABLE_READ");
        registry.add("spring.jpa.properties.hibernate.connection.isolation",
                () -> String.valueOf(java.sql.Connection.TRANSACTION_REPEATABLE_READ));
    }

    @BeforeEach
    void startWithAnEmptyOutbox() {
        repository.deleteAll();
        repository.flush();
        when(userSettingsService.shouldDeliver(anyLong(), anyString())).thenReturn(true);
    }

    @ParameterizedTest(name = "clock advance {0}s")
    @ValueSource(longs = {29, 31})
    void delayed_commit_does_not_return_a_delivery_with_an_obsolete_attempt_snapshot(
            long clockAdvanceSeconds
    ) throws Exception {
        Long rowId = seedPending(T0.minusSeconds(1));
        Instant bNow = T0.plusSeconds(clockAdvanceSeconds);
        CountDownLatch aClaimed = new CountDownLatch(1);
        CountDownLatch bBeforeCas = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch releaseB = new CountDownLatch(1);
        AtomicInteger bCasResult = new AtomicInteger(Integer.MIN_VALUE);
        AtomicInteger bIsolation = new AtomicInteger(Integer.MIN_VALUE);
        NotificationOutboxRepository bRepository = forwardingRepository(
                aClaimed,
                bBeforeCas,
                releaseB,
                bCasResult
        );
        NotificationOutboxService serviceA = service(repository, Clock.fixed(T0, ZoneOffset.UTC));
        NotificationOutboxService serviceB = service(bRepository, Clock.fixed(bNow, ZoneOffset.UTC));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Optional<NotificationOutboxService.Delivery>> aFuture = null;
        Future<Optional<NotificationOutboxService.Delivery>> bFuture = null;

        try {
            aFuture = executor.submit(() -> inTransaction(() -> {
                Optional<NotificationOutboxService.Delivery> result = serviceA.claimOne(A_OWNER);
                if (result.isEmpty()) {
                    throw new AssertionError("A failed to claim the seeded pending row");
                }
                aClaimed.countDown();
                awaitLatch(releaseA, "A commit release");
                return result;
            }));

            awaitLatch(aClaimed, "A claim barrier");
            bFuture = executor.submit(() -> inTransaction(() -> {
                jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                    bIsolation.set(connection.getTransactionIsolation());
                    return null;
                });
                return serviceB.claimOne(B_OWNER);
            }));
            awaitLatch(bBeforeCas, "B candidate-read/CAS barrier");

            // B's candidate query ran at T1 while A's claim is still
            // uncommitted, so it has the old PENDING/attempts=0 snapshot.
            releaseA.countDown();
            Optional<NotificationOutboxService.Delivery> aDelivery = future(aFuture, "A claim transaction");
            assertThat(aDelivery).as("A must commit its lease before B's CAS").isPresent();
            releaseB.countDown();
            Optional<NotificationOutboxService.Delivery> bDelivery = future(bFuture, "B claim transaction");
            OutboxSnapshot afterB = readBack(rowId);

            if (bDelivery.isPresent()) {
                NotificationOutboxService.Delivery delivery = bDelivery.orElseThrow();
                boolean markedFailed = inTransaction(() -> serviceB.markFailedOrRetry(
                        delivery,
                        B_OWNER,
                        new IllegalStateException("snapshot-baseline-failure")
                ));
                OutboxSnapshot afterFailure = readBack(rowId);
                printMarker(clockAdvanceSeconds, rowId, aDelivery.orElseThrow(), bDelivery, afterB,
                        markedFailed, afterFailure, bCasResult.get(), bIsolation.get(), null);

                SoftAssertions softly = new SoftAssertions();
                softly.assertThat(delivery.attempts())
                        .as("a successful B delivery must carry the persisted attempt count")
                        .isEqualTo(afterB.attempts());
                softly.assertThat(markedFailed)
                        .as("B's owner should still be able to finish its successful claim")
                        .isTrue();
                softly.assertThat(afterFailure.status())
                        .as("a correctly refreshed attempt=2 delivery reaches maxAttempts=2")
                        .isEqualTo(NotificationOutboxStatus.FAILED);
                softly.assertThat(afterFailure.attempts()).isEqualTo(2);
                softly.assertAll();
            } else {
                printMarker(clockAdvanceSeconds, rowId, aDelivery.orElseThrow(), bDelivery, afterB,
                        false, null, bCasResult.get(), bIsolation.get(), null);
                SoftAssertions softly = new SoftAssertions();
                softly.assertThat(afterB.status()).isEqualTo(NotificationOutboxStatus.IN_FLIGHT);
                softly.assertThat(afterB.leaseOwner())
                        .as("a stale B snapshot must not mutate A's committed lease")
                        .isEqualTo(A_OWNER);
                softly.assertThat(afterB.attempts()).isEqualTo(1);
                softly.assertThat(bCasResult.get()).isEqualTo(0);
                softly.assertAll();

                // A fresh candidate read at T0+31 can now reclaim the expired
                // A lease; this is the positive path after a safe B decline.
                NotificationOutboxService serviceC = service(repository, Clock.fixed(T1, ZoneOffset.UTC));
                Optional<NotificationOutboxService.Delivery> cDelivery = inTransaction(
                        () -> serviceC.claimOne(C_OWNER)
                );
                OutboxSnapshot afterC = readBack(rowId);
                printMarker(clockAdvanceSeconds, rowId, aDelivery.orElseThrow(), bDelivery, afterC,
                        false, null, bCasResult.get(), bIsolation.get(), cDelivery);
                assertThat(cDelivery).as("fresh claim after B's safe decline").isPresent();
                assertThat(cDelivery.orElseThrow().attempts()).isEqualTo(2);
                assertThat(afterC.status()).isEqualTo(NotificationOutboxStatus.IN_FLIGHT);
                assertThat(afterC.leaseOwner()).isEqualTo(C_OWNER);
                assertThat(afterC.attempts()).isEqualTo(2);
                boolean aMarkedDelivered = inTransaction(() -> serviceA.markDelivered(rowId, A_OWNER));
                assertThat(aMarkedDelivered)
                        .as("A's stale owner cannot finish after C reclaims the row")
                        .isFalse();
                boolean cMarkedFailed = inTransaction(() -> serviceC.markFailedOrRetry(
                        cDelivery.orElseThrow(),
                        C_OWNER,
                        new IllegalStateException("snapshot-fresh-failure")
                ));
                OutboxSnapshot afterCFailure = readBack(rowId);
                printMarker(clockAdvanceSeconds, rowId, aDelivery.orElseThrow(), bDelivery, afterCFailure,
                        cMarkedFailed, afterCFailure, bCasResult.get(), bIsolation.get(), cDelivery);
                assertThat(cMarkedFailed).isTrue();
                assertThat(afterCFailure.status()).isEqualTo(NotificationOutboxStatus.FAILED);
                assertThat(afterCFailure.attempts()).isEqualTo(2);
            }
        } finally {
            releaseA.countDown();
            releaseB.countDown();
            if (aFuture != null) {
                aFuture.cancel(true);
            }
            if (bFuture != null) {
                bFuture.cancel(true);
            }
            executor.shutdownNow();
            executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private NotificationOutboxRepository forwardingRepository(
            CountDownLatch aClaimed,
            CountDownLatch bBeforeCas,
            CountDownLatch releaseB,
            AtomicInteger bCasResult
    ) {
        NotificationOutboxRepository forwarding = mock(
                NotificationOutboxRepository.class,
                AdditionalAnswers.delegatesTo(repository)
        );
        doAnswer(invocation -> {
            if (aClaimed.getCount() == 0) {
                bBeforeCas.countDown();
            }
            awaitLatch(releaseB, "B CAS permission");
            int result = repository.claim(
                    invocation.getArgument(0, Long.class),
                    invocation.getArgument(1, NotificationOutboxStatus.class),
                    invocation.getArgument(2, NotificationOutboxStatus.class),
                    invocation.getArgument(3, Instant.class),
                    invocation.getArgument(4, String.class),
                    invocation.getArgument(5, Instant.class),
                    invocation.getArgument(6, Integer.class)
            );
            bCasResult.set(result);
            return result;
        }).when(forwarding).claim(
                anyLong(),
                eq(NotificationOutboxStatus.PENDING),
                eq(NotificationOutboxStatus.IN_FLIGHT),
                any(Instant.class),
                anyString(),
                any(Instant.class),
                anyInt()
        );
        return forwarding;
    }

    private NotificationOutboxService service(
            NotificationOutboxRepository outboxRepository,
            Clock clock
    ) {
        return new NotificationOutboxService(
                outboxRepository,
                userSettingsService,
                clock,
                LEASE,
                1,
                2,
                BACKOFF,
                RETENTION
        );
    }

    private Long seedPending(Instant availableAt) {
        NotificationOutbox row = new NotificationOutbox();
        row.setEventId(UUID.randomUUID().toString());
        row.setRecipientUserId(101L);
        row.setType("reply");
        row.setTitle("Title");
        row.setContent("Content");
        row.setStatus(NotificationOutboxStatus.PENDING);
        row.setAttempts(0);
        row.setAvailableAt(availableAt);
        row.setCreatedAt(availableAt);
        row.setUpdatedAt(availableAt);
        return inTransaction(() -> repository.saveAndFlush(row).getId());
    }

    private <T> T inTransaction(java.util.concurrent.Callable<T> action) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            try {
                return action.call();
            } catch (Exception exception) {
                throw new IllegalStateException("transaction callback failed", exception);
            }
        });
    }

    private Optional<NotificationOutboxService.Delivery> future(
            Future<Optional<NotificationOutboxService.Delivery>> future,
            String name
    ) throws Exception {
        try {
            return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            throw new AssertionError(name + " failed", exception.getCause());
        }
    }

    private OutboxSnapshot readBack(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT status, attempts, lease_owner, lease_until FROM notification_outbox WHERE id = ?",
                (resultSet, rowNum) -> new OutboxSnapshot(
                        NotificationOutboxStatus.valueOf(resultSet.getString("status")),
                        resultSet.getInt("attempts"),
                        resultSet.getString("lease_owner"),
                        Optional.ofNullable(resultSet.getTimestamp("lease_until"))
                                .map(Timestamp::toInstant)
                                .orElse(null)
                ),
                id
        );
    }

    private static void awaitLatch(CountDownLatch latch, String name) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for " + name);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for " + name, exception);
        }
    }

    private static String environment(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }

    private static void validateMySqlFixture(String url) {
        if (!url.startsWith("jdbc:mysql://")) {
            throw new IllegalStateException(MYSQL_URL_ENV + " must use jdbc:mysql://");
        }
        URI uri;
        try {
            uri = URI.create(url.substring("jdbc:".length()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(MYSQL_URL_ENV + " is not a valid JDBC URL", exception);
        }
        String host = uri.getHost();
        if (!("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host))
                || uri.getPort() != MYSQL_PORT) {
            throw new IllegalStateException(MYSQL_URL_ENV
                    + " must target loopback port " + MYSQL_PORT);
        }
        String path = uri.getPath();
        String schema = path == null || path.length() < 2
                ? ""
                : path.substring(1).split("/", 2)[0];
        if (!schema.startsWith(MYSQL_SCHEMA_PREFIX)
                || schema.length() == MYSQL_SCHEMA_PREFIX.length()) {
            throw new IllegalStateException(MYSQL_URL_ENV
                    + " must target a schema beginning with " + MYSQL_SCHEMA_PREFIX);
        }
    }

    private static void printMarker(
            long clockAdvanceSeconds,
            Long rowId,
            NotificationOutboxService.Delivery aDelivery,
            Optional<NotificationOutboxService.Delivery> bDelivery,
            OutboxSnapshot snapshot,
            boolean markedFailed,
            OutboxSnapshot afterFailure,
            int bCasResult,
            int bIsolation,
            Optional<NotificationOutboxService.Delivery> cDelivery
    ) {
        String failure = afterFailure == null
                ? "none"
                : afterFailure.status() + "/attempts=" + afterFailure.attempts();
        String fresh = cDelivery == null
                ? "none"
                : cDelivery.map(delivery -> "present/attempts=" + delivery.attempts()).orElse("empty");
        System.out.println("OUTBOX_CLAIM_SNAPSHOT "
                + "clockAdvanceSeconds=" + clockAdvanceSeconds
                + " row=" + rowId
                + " a=present/attempts=" + aDelivery.attempts()
                + " b=" + bDelivery.map(delivery -> "present/attempts=" + delivery.attempts()).orElse("empty")
                + " bCas=" + bCasResult
                + " bIsolation=" + bIsolation
                + " db=" + snapshot.status() + "/owner=" + snapshot.leaseOwner() + "/attempts=" + snapshot.attempts()
                + " markedFailed=" + markedFailed
                + " afterFailure=" + failure
                + " c=" + fresh);
        System.out.flush();
    }

    private record OutboxSnapshot(
            NotificationOutboxStatus status,
            int attempts,
            String leaseOwner,
            Instant leaseUntil
    ) {
    }
}
