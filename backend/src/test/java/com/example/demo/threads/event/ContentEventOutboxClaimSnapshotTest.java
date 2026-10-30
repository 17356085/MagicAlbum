package com.example.demo.threads.event;

import com.example.demo.threads.event.config.ContentEventProperties;
import com.example.demo.threads.event.entity.ContentEventOutbox;
import com.example.demo.threads.event.entity.ContentEventStatus;
import com.example.demo.threads.event.entity.ContentEventType;
import com.example.demo.threads.event.repo.ContentEventOutboxRepository;
import com.example.demo.threads.event.service.ContentEventOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest(classes = com.example.demo.Demo1Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisabledInAotMode
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:content-claim-snapshot-20261030;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.jpa.show-sql=false",
        "app.rabbit.enabled=false",
        "app.content.events.enabled=false"
})
class ContentEventOutboxClaimSnapshotTest {
    private static final Instant T0 = Instant.parse("2026-10-30T12:00:00Z");
    private static final long WAIT_SECONDS = 10;
    private static final String MYSQL_URL_ENV = "CONTENT_CLAIM_TEST_MYSQL_URL";
    private static final String MYSQL_USER_ENV = "CONTENT_CLAIM_TEST_MYSQL_USER";
    private static final String MYSQL_PASSWORD_ENV = "CONTENT_CLAIM_TEST_MYSQL_PASSWORD";
    private static final String MYSQL_SCHEMA_PREFIX = "content_claim_acceptance_";

    @Autowired private ContentEventOutboxRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void isolatedMysqlFixture(DynamicPropertyRegistry registry) throws Exception {
        String url = environment(MYSQL_URL_ENV);
        if (url.isEmpty()) return;
        URI uri = URI.create(url.substring("jdbc:".length()));
        String schema = uri.getPath().substring(1);
        String marker = schema.startsWith(MYSQL_SCHEMA_PREFIX) ? schema.substring(MYSQL_SCHEMA_PREFIX.length()) : "";
        String user = environment(MYSQL_USER_ENV);
        String password = environment(MYSQL_PASSWORD_ENV);
        if (!url.startsWith("jdbc:mysql://") || !"127.0.0.1".equals(uri.getHost()) || uri.getPort() != 33184
                || !marker.matches("[a-f0-9]{32}") || !"content_claim_fixture".equals(user) || password.isEmpty()) {
            throw new IllegalStateException("MySQL fixture must use the dedicated loopback schema and user");
        }
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.prepareStatement("SELECT marker FROM codex_claim_test_guard");
             var result = statement.executeQuery()) {
            if (!result.next() || !marker.equals(result.getString(1)) || result.next()) {
                throw new IllegalStateException("MySQL fixture guard did not match its dedicated schema");
            }
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
    void clearIsolatedFixture() {
        repository.deleteAll();
        repository.flush();
    }

    @ParameterizedTest(name = "delayed commit at {0}s")
    @ValueSource(longs = {29, 31})
    void delayedCommitCannotProduceAnObsoleteAttemptSnapshot(long advanceSeconds) throws Exception {
        Long id = seed();
        var aClaimed = new CountDownLatch(1);
        var bBeforeClaim = new CountDownLatch(1);
        var releaseA = new CountDownLatch(1);
        var releaseB = new CountDownLatch(1);
        ContentEventOutboxRepository forwarding = mock(ContentEventOutboxRepository.class, invocation -> {
            if ("claim".equals(invocation.getMethod().getName())) {
                bBeforeClaim.countDown();
                await(releaseB);
            }
            try {
                return invocation.getMethod().invoke(repository, invocation.getArguments());
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
        ContentEventOutboxService a = service(repository, T0);
        Instant bNow = T0.plusSeconds(advanceSeconds);
        ContentEventOutboxService b = service(forwarding, bNow);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> inTransaction(() -> {
                var delivery = a.claimOne("owner-a");
                if (delivery.isEmpty()) throw new AssertionError("A did not claim the fixture");
                aClaimed.countDown();
                await(releaseA);
                return delivery;
            }));
            try {
                await(aClaimed);
                var second = executor.submit(() -> inTransaction(() -> b.claimOne("owner-b")));
                await(bBeforeClaim);
                releaseA.countDown();
                var aDelivery = first.get(WAIT_SECONDS, TimeUnit.SECONDS).orElseThrow();
                releaseB.countDown();
                Optional<ContentEventOutboxService.Delivery> bDelivery = second.get(WAIT_SECONDS, TimeUnit.SECONDS);
                Snapshot afterB = snapshot(id);
                if (bDelivery.isPresent()) {
                    var delivery = bDelivery.orElseThrow();
                    boolean marked = inTransaction(() -> b.markFailure(delivery, new IllegalStateException("simulated")));
                    Snapshot afterFailure = snapshot(id);
                    System.out.printf("CONTENT_CLAIM_SNAPSHOT advance=%d bAttempts=%d dbAttempts=%d marked=%s failureStatus=%s%n",
                            advanceSeconds, delivery.attempts(), afterB.attempts(), marked, afterFailure.status());
                    assertThat(delivery.attempts()).as("returned count must match the claimed database row")
                            .isEqualTo(afterB.attempts());
                    assertThat(afterFailure.status()).isEqualTo(ContentEventStatus.FAILED);
                }
                assertThat(bDelivery).as("a stale candidate must not claim a newer attempt").isEmpty();
                ContentEventOutboxService c = service(repository, T0.plusSeconds(31));
                var fresh = inTransaction(() -> c.claimOne("owner-c")).orElseThrow();
                assertThat(fresh.attempts()).isEqualTo(2);
                assertThat(inTransaction(() -> a.markPublished(aDelivery))).isFalse();
                assertThat(inTransaction(() -> c.markFailure(fresh, new IllegalStateException("simulated")))).isTrue();
                Snapshot afterC = snapshot(id);
                assertThat(afterC.attempts()).isEqualTo(2);
                assertThat(afterC.status()).isEqualTo(ContentEventStatus.FAILED);
                System.out.printf("CONTENT_CLAIM_SNAPSHOT advance=%d stale=empty freshAttempts=%d finalStatus=%s%n",
                        advanceSeconds, fresh.attempts(), afterC.status());
            } finally {
                releaseA.countDown();
                releaseB.countDown();
            }
        }
    }

    private ContentEventOutboxService service(ContentEventOutboxRepository source, Instant now) {
        var properties = new ContentEventProperties();
        properties.setMaxAttempts(2);
        return new ContentEventOutboxService(source, properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    private Long seed() {
        ContentEventOutbox row = new ContentEventOutbox();
        row.setEventId(UUID.randomUUID().toString());
        row.setSchemaVersion(1);
        row.setAggregateType("THREAD");
        row.setAggregateId(1L);
        row.setAggregateVersion(System.nanoTime());
        row.setEventType(ContentEventType.THREAD_UPSERTED);
        row.setOccurredAt(T0.minusSeconds(1));
        row.setStatus(ContentEventStatus.PENDING);
        row.setAttempts(0);
        row.setAvailableAt(T0.minusSeconds(1));
        return inTransaction(() -> repository.saveAndFlush(row).getId());
    }

    private Snapshot snapshot(Long id) {
        return jdbc.queryForObject("SELECT status,attempts FROM content_event_outbox WHERE id=?",
                (rs, row) -> new Snapshot(ContentEventStatus.valueOf(rs.getString("status")), rs.getInt("attempts")), id);
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) throw new AssertionError("barrier timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("barrier interrupted", interrupted);
        }
    }

    private static String environment(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }

    private record Snapshot(ContentEventStatus status, int attempts) {}
}
