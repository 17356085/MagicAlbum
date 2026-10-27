package com.example.notification.service;

import com.example.notification.dto.CreateNotificationRequest;
import com.example.notification.dto.NotificationDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * Real-database evidence for first-owner stream-cursor initialization.
 *
 * <p>B and C establish a real consistent-read snapshot before A creates the
 * first cursor row. They then let the real append implementation continue.
 * The JdbcTemplate spy only observes the real duplicate-key exception and
 * keeps the two old-path SELECT FOR UPDATE calls aligned; it never supplies a
 * fabricated query or update result.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisabledInAotMode
class NotificationCursorInitializationTest {
    private static final Long OWNER = 10801L;
    private static final Long ROLLBACK_OWNER = 10802L;
    private static final String DATABASE = "notification_cursor_" + UUID.randomUUID();
    private static final String CURSOR_COUNT_SQL =
            "SELECT COUNT(*) FROM notification_stream_cursor WHERE user_id=?";
    private static final String CURSOR_INSERT_PREFIX =
            "INSERT INTO notification_stream_cursor";
    private static final String CURSOR_SELECT_PREFIX =
            "SELECT next_sequence FROM notification_stream_cursor WHERE user_id=? FOR UPDATE";

    @Autowired PersistentNotificationService notifications;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean NotificationEventLog events;
    @MockitoSpyBean JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        String mysqlUrl = System.getenv("NOTIFICATION_CURSOR_MYSQL_URL");
        if (mysqlUrl == null || mysqlUrl.isBlank()) {
            properties.add("spring.datasource.url", () ->
                    "jdbc:h2:mem:" + DATABASE + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
            properties.add("spring.datasource.username", () -> "sa");
            properties.add("spring.datasource.password", () -> "");
            properties.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
            return;
        }
        if (!mysqlUrl.matches("jdbc:mysql://127[.]0[.]0[.]1:33183/notification_stream_acceptance_[a-z0-9_]+(?:[?].*)?")) {
            throw new IllegalArgumentException(
                    "Expected a dedicated loopback notification_stream_acceptance_ MySQL schema on port 33183");
        }
        String username = System.getenv("NOTIFICATION_CURSOR_MYSQL_USER");
        String password = System.getenv("NOTIFICATION_CURSOR_MYSQL_PASSWORD");
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("Dedicated MySQL test credentials are required");
        }
        properties.add("spring.datasource.url", () -> mysqlUrl);
        properties.add("spring.datasource.username", () -> username);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        properties.add("spring.datasource.hikari.transaction-isolation", () -> "TRANSACTION_REPEATABLE_READ");
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM notification_stream_event");
        jdbc.update("DELETE FROM notification_stream_cursor");
        jdbc.update("DELETE FROM notifications");
    }

    @Test
    void distinctFirstOwnerEventsInitializeOneDurableCursorWithoutGaps() throws Exception {
        CyclicBarrier preCountBarrier = new CyclicBarrier(3);
        CyclicBarrier duplicateSelectBarrier = new CyclicBarrier(2);
        CountDownLatch releaseAppend = new CountDownLatch(1);
        ThreadLocal<Role> role = new ThreadLocal<>();
        ThreadLocal<Boolean> sawDuplicateCursorInsert = ThreadLocal.withInitial(() -> false);
        ConcurrentLinkedQueue<IsolationMarker> isolationMarkers = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<CountMarker> preCountMarkers = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Role> duplicateInsertRoles = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Role> duplicateSelectRoles = new ConcurrentLinkedQueue<>();
        ConcurrentHashMap<String, AtomicInteger> duplicateExceptionCounts = new ConcurrentHashMap<>();
        ConcurrentLinkedQueue<String> fixtureExceptions = new ConcurrentLinkedQueue<>();
        List<String> futureExceptions = new ArrayList<>();

        NotificationEventLog eventTarget = AopTestUtils.getUltimateTargetObject(events);
        instrumentUpdates(role, sawDuplicateCursorInsert, duplicateInsertRoles, duplicateExceptionCounts);
        instrumentCursorSelect(
                role,
                sawDuplicateCursorInsert,
                duplicateSelectBarrier,
                duplicateSelectRoles,
                fixtureExceptions
        );
        doAnswer(invocation -> {
            Role currentRole = role.get();
            if (currentRole == Role.B || currentRole == Role.C) {
                int isolation = jdbc.execute((ConnectionCallback<Integer>) Connection::getTransactionIsolation);
                isolationMarkers.add(new IsolationMarker(currentRole, isolation));
                Long count = jdbc.queryForObject(CURSOR_COUNT_SQL, Long.class, OWNER);
                preCountMarkers.add(new CountMarker(currentRole, count == null ? -1L : count));
                awaitBarrier(preCountBarrier, "pre-count", fixtureExceptions);
                awaitLatch(releaseAppend, "release-append", fixtureExceptions);
            }
            return invocation.callRealMethod();
        }).when(eventTarget).append(any(Long.class), any(Long.class), anyString());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<NotificationDto>> futures = new ArrayList<>();
        NotificationDto firstOwnerNotification = null;
        List<NotificationDto> returned = new ArrayList<>();
        try {
            futures.add(submitCreate(pool, role, Role.B));
            futures.add(submitCreate(pool, role, Role.C));

            awaitBarrier(preCountBarrier, "pre-count", fixtureExceptions);

            try {
                role.set(Role.A);
                firstOwnerNotification = notifications.create(request(Role.A));
            } catch (Throwable failure) {
                fixtureExceptions.add(exceptionClass(failure));
            } finally {
                role.remove();
            }
            releaseAppend.countDown();

            for (Future<NotificationDto> future : futures) {
                try {
                    returned.add(future.get(10, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    futureExceptions.add(exceptionClass(interrupted));
                } catch (ExecutionException | TimeoutException failure) {
                    futureExceptions.add(exceptionClass(failure));
                }
            }
            if (firstOwnerNotification != null) {
                returned.add(firstOwnerNotification);
            }

            long notificationCount = queryLong(
                    "SELECT COUNT(*) FROM notifications WHERE user_id=?", OWNER);
            long cursor = queryLong(
                    "SELECT next_sequence FROM notification_stream_cursor WHERE user_id=?", OWNER);
            List<StreamRow> streamRows = jdbc.query(
                    "SELECT user_id,sequence_id,notification_id,event_kind "
                            + "FROM notification_stream_event ORDER BY user_id,sequence_id",
                    (rs, rowNum) -> new StreamRow(
                            rs.getLong("user_id"),
                            rs.getLong("sequence_id"),
                            rs.getLong("notification_id"),
                            rs.getString("event_kind")
                    )
            );
            List<String> isolation = isolationMarkers.stream()
                    .map(marker -> marker.role() + "=" + marker.value())
                    .sorted()
                    .toList();
            List<String> preCounts = preCountMarkers.stream()
                    .map(marker -> marker.role() + "=" + marker.value())
                    .sorted()
                    .toList();
            List<String> duplicateRoles = duplicateInsertRoles.stream()
                    .map(Role::name)
                    .sorted()
                    .toList();
            List<String> selectRoles = duplicateSelectRoles.stream()
                    .map(Role::name)
                    .sorted()
                    .toList();
            Map<String, Integer> duplicateExceptions = duplicateExceptionCounts.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().get()
                    ));
            System.out.printf(
                    "NOTIFICATION_CURSOR_INIT_OBSERVATION db=%s isolation=%s preCounts=%s "
                            + "duplicateCursorInsertRoles=%s duplicateSelectRoles=%s "
                            + "duplicateExceptionCounts=%s futureExceptions=%s fixtureExceptions=%s "
                            + "returned=%d notifications=%d cursor=%d events=%s%n",
                    databaseMode(),
                    isolation,
                    preCounts,
                    duplicateRoles,
                    selectRoles,
                    duplicateExceptions,
                    futureExceptions,
                    fixtureExceptions,
                    returned.size(),
                    notificationCount,
                    cursor,
                    streamRows.stream().map(StreamRow::compact).toList()
            );

            assertThat(fixtureExceptions).as("bounded fixture coordination").isEmpty();
            assertThat(futureExceptions).as("create futures must complete").isEmpty();
            assertThat(returned).hasSize(3).doesNotContainNull();
            assertThat(returned).extracting(NotificationDto::getId).doesNotHaveDuplicates();
            assertThat(preCountMarkers).extracting(CountMarker::value).containsExactlyInAnyOrder(0L, 0L);
            int expectedIsolation = mysqlConfigured()
                    ? Connection.TRANSACTION_REPEATABLE_READ
                    : Connection.TRANSACTION_READ_COMMITTED;
            assertThat(isolationMarkers).extracting(IsolationMarker::value)
                    .containsExactlyInAnyOrder(expectedIsolation, expectedIsolation);
            assertThat(notificationCount).isEqualTo(3L);
            assertThat(cursor).isEqualTo(3L);
            assertThat(streamRows).hasSize(3);
            assertThat(streamRows).extracting(StreamRow::owner).containsOnly(OWNER);
            assertThat(streamRows).extracting(StreamRow::sequence)
                    .containsExactly(1L, 2L, 3L);
            assertThat(streamRows).extracting(StreamRow::kind)
                    .containsExactly("created", "created", "created");
        } finally {
            releaseAppend.countDown();
            preCountBarrier.reset();
            duplicateSelectBarrier.reset();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS))
                    .as("executor must terminate during cleanup")
                    .isTrue();
        }
    }

    @Test
    void failedFirstAllocationRollsBackCursorBeforeLaterValidCreate() {
        long missingNotificationId = Long.MAX_VALUE;
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            events.append(ROLLBACK_OWNER, missingNotificationId, "created");
            return null;
        })).isInstanceOf(DataAccessException.class);

        assertThat(queryLong(
                "SELECT COUNT(*) FROM notification_stream_cursor WHERE user_id=?", ROLLBACK_OWNER
        )).isZero();
        assertThat(events.current(ROLLBACK_OWNER)).isZero();
        assertThat(queryLong(
                "SELECT COUNT(*) FROM notification_stream_event WHERE user_id=?", ROLLBACK_OWNER
        )).isZero();

        NotificationDto created = notifications.create(request(Role.A, ROLLBACK_OWNER));
        assertThat(created).isNotNull();
        assertThat(events.current(ROLLBACK_OWNER)).isEqualTo(1L);
        assertThat(events.after(ROLLBACK_OWNER, 0, 100))
                .extracting(NotificationEventLog.Event::cursor, NotificationEventLog.Event::kind)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("1", "created"));
        assertThat(queryLong(
                "SELECT COUNT(*) FROM notifications WHERE user_id=?", ROLLBACK_OWNER
        )).isEqualTo(1L);
    }

    private Future<NotificationDto> submitCreate(
            ExecutorService pool,
            ThreadLocal<Role> role,
            Role currentRole
    ) {
        return pool.submit(() -> {
            role.set(currentRole);
            try {
                return notifications.create(request(currentRole));
            } finally {
                role.remove();
            }
        });
    }

    private CreateNotificationRequest request(Role role) {
        return request(role, OWNER);
    }

    private CreateNotificationRequest request(Role role, Long owner) {
        CreateNotificationRequest request = new CreateNotificationRequest();
        request.setEventId(UUID.randomUUID());
        request.setUserId(owner);
        request.setType("system");
        request.setTitle("cursor-init-" + role.name().toLowerCase(Locale.ROOT));
        request.setContent("cursor initialization evidence");
        return request;
    }

    private void instrumentUpdates(
            ThreadLocal<Role> role,
            ThreadLocal<Boolean> sawDuplicateCursorInsert,
            ConcurrentLinkedQueue<Role> duplicateInsertRoles,
            ConcurrentHashMap<String, AtomicInteger> duplicateExceptionCounts
    ) {
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            try {
                return invocation.callRealMethod();
            } catch (DuplicateKeyException duplicate) {
                if (sql.trim().startsWith(CURSOR_INSERT_PREFIX)) {
                    Role currentRole = role.get();
                    if (currentRole == Role.B || currentRole == Role.C) {
                        duplicateInsertRoles.add(currentRole);
                        sawDuplicateCursorInsert.set(true);
                        duplicateExceptionCounts
                                .computeIfAbsent(duplicate.getClass().getName(), ignored -> new AtomicInteger())
                                .incrementAndGet();
                    }
                }
                throw duplicate;
            }
        }).when(jdbc).update(anyString(), any(Object[].class));
    }

    private void instrumentCursorSelect(
            ThreadLocal<Role> role,
            ThreadLocal<Boolean> sawDuplicateCursorInsert,
            CyclicBarrier duplicateSelectBarrier,
            ConcurrentLinkedQueue<Role> duplicateSelectRoles,
            ConcurrentLinkedQueue<String> fixtureExceptions
    ) {
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            Role currentRole = role.get();
            if (CURSOR_SELECT_PREFIX.equals(sql)
                    && (currentRole == Role.B || currentRole == Role.C)
                    && sawDuplicateCursorInsert.get()) {
                awaitBarrier(duplicateSelectBarrier, "duplicate-select", fixtureExceptions);
                duplicateSelectRoles.add(currentRole);
            }
            return invocation.callRealMethod();
        }).when(jdbc).queryForObject(anyString(), eq(Long.class), any(Object[].class));
    }

    private long queryLong(String sql, Object argument) {
        Long value = jdbc.queryForObject(sql, Long.class, argument);
        return value == null ? 0L : value;
    }

    private String databaseMode() {
        return mysqlConfigured() ? "mysql" : "h2";
    }

    private static boolean mysqlConfigured() {
        String url = System.getenv("NOTIFICATION_CURSOR_MYSQL_URL");
        return url != null && !url.isBlank();
    }

    private static void awaitBarrier(
            CyclicBarrier barrier,
            String name,
            ConcurrentLinkedQueue<String> failures
    ) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            failures.add(name + ":" + exceptionClass(failure));
            throw new IllegalStateException("cursor fixture " + name + " barrier failed", failure);
        }
    }

    private static void awaitLatch(
            CountDownLatch latch,
            String name,
            ConcurrentLinkedQueue<String> failures
    ) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                failures.add(name + ":TimeoutException");
                throw new IllegalStateException("cursor fixture " + name + " latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failures.add(name + ":" + exceptionClass(interrupted));
            throw new IllegalStateException("cursor fixture " + name + " latch interrupted", interrupted);
        }
    }

    private static String exceptionClass(Throwable failure) {
        Throwable current = failure;
        while (current instanceof ExecutionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getName();
    }

    private record IsolationMarker(Role role, int value) {
    }

    private record CountMarker(Role role, long value) {
    }

    private record StreamRow(long owner, long sequence, long notificationId, String kind) {
        String compact() {
            return owner + ":" + sequence + ":" + notificationId + ":" + kind;
        }
    }

    private enum Role {
        A,
        B,
        C
    }
}
