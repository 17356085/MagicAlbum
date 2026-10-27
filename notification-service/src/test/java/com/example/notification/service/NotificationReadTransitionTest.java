package com.example.notification.service;

import com.example.notification.dto.CreateNotificationRequest;
import com.example.notification.dto.NotificationDto;
import com.example.notification.entity.Notification;
import com.example.notification.repo.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisabledInAotMode
class NotificationReadTransitionTest {
    private static final Long OWNER = 10701L;
    private static final String DATABASE = "notification_read_" + UUID.randomUUID();

    @Autowired PersistentNotificationService notifications;
    @MockitoSpyBean NotificationRepository repository;
    @MockitoSpyBean NotificationEventLog events;
    @MockitoSpyBean JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        String mysqlUrl = System.getenv("BLUEALBUM_NOTIFICATION_TEST_MYSQL_URL");
        if (mysqlUrl == null || mysqlUrl.isBlank()) {
            properties.add("spring.datasource.url", () -> "jdbc:h2:mem:" + DATABASE + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            return;
        }
        // Optional acceptance runs must never clean an ordinary application schema.
        if (!mysqlUrl.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/notification_read_acceptance_[a-z0-9_]+([?].*)?")) {
            throw new IllegalArgumentException("Expected a dedicated loopback notification_read_acceptance_ schema");
        }
        String username = System.getenv("BLUEALBUM_NOTIFICATION_TEST_MYSQL_USER");
        String password = System.getenv("BLUEALBUM_NOTIFICATION_TEST_MYSQL_PASSWORD");
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("Dedicated MySQL test credentials are required");
        }
        properties.add("spring.datasource.url", () -> mysqlUrl);
        properties.add("spring.datasource.username", () -> username);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM notification_stream_event");
        jdbc.update("DELETE FROM notification_stream_cursor");
        jdbc.update("DELETE FROM notifications");
    }

    @Test
    void alreadyReadReplaysReturnTheSameResourceWithoutAnotherUpdate() {
        NotificationDto created = create();
        NotificationDto first = notifications.markRead(OWNER, created.getId());
        assertThat(first.isRead()).isTrue();
        assertThat(first.getTitle()).isEqualTo("Title");
        assertThat(first.getLink()).isEqualTo("/threads/7#post-8");
        long cursor = events.current(OWNER);
        clearInvocations(jdbc);

        for (int replay = 0; replay < 5; replay++) {
            NotificationDto result = notifications.markRead(OWNER, created.getId());
            assertThat(result.getId()).isEqualTo(created.getId());
            assertThat(result.isRead()).isTrue();
        }
        long updates = readUpdateCalls();
        System.out.printf("NOTIFICATION_READ_MEASURE scenario=already-read replays=5 updates=%d events=%d%n",
                updates, readEvents());
        assertThat(updates).isZero();
        assertThat(events.current(OWNER)).isEqualTo(cursor);
        assertThat(readEvents()).isEqualTo(1);
    }

    @Test
    void concurrentUnreadSnapshotsPublishOneReadEventForOneStateTransition() throws Exception {
        NotificationDto created = create();
        int callers = 6;
        var snapshots = new CyclicBarrier(callers);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Optional<Notification> result = (Optional<Notification>) invocation.callRealMethod();
            assertThat(result).isPresent();
            assertThat(result.orElseThrow().isRead()).isFalse();
            snapshots.await(5, TimeUnit.SECONDS);
            return result;
        }).when(repository).findByIdAndUserId(created.getId(), OWNER);

        List<NotificationDto> returned = new ArrayList<>();
        try (var workers = Executors.newFixedThreadPool(callers)) {
            List<Future<NotificationDto>> pending = new ArrayList<>();
            for (int caller = 0; caller < callers; caller++) {
                pending.add(workers.submit(() -> notifications.markRead(OWNER, created.getId())));
            }
            for (Future<NotificationDto> result : pending) returned.add(result.get(10, TimeUnit.SECONDS));
        }
        assertThat(returned).allSatisfy(result -> {
            assertThat(result.getId()).isEqualTo(created.getId());
            assertThat(result.isRead()).isTrue();
        });
        long count = readEvents();
        System.out.printf("NOTIFICATION_READ_MEASURE scenario=concurrent-unread callers=%d readEvents=%d cursor=%d%n",
                callers, count, events.current(OWNER));
        assertThat(count).isEqualTo(1);
        assertThat(events.current(OWNER)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT read_flag FROM notifications WHERE id=?", Boolean.class, created.getId())).isTrue();
    }

    @Test
    void anotherOwnersNotificationAndMissingResourcesCannotBeUpdatedOrPublished() {
        NotificationDto created = create();
        clearInvocations(jdbc);
        assertThat(notifications.markRead(OWNER + 1, created.getId())).isNull();
        assertThat(notifications.markRead(OWNER, Long.MAX_VALUE)).isNull();
        assertThat(readUpdateCalls()).isZero();
        assertThat(readEvents()).isZero();
        assertThat(events.current(OWNER + 1)).isZero();
        assertThat(jdbc.queryForObject("SELECT read_flag FROM notifications WHERE id=?", Boolean.class, created.getId())).isFalse();
    }

    @Test
    void eventFailureRollsBackTheReadFlagAndAllowsAnAtomicLaterRetry() {
        NotificationDto created = create();
        NotificationEventLog eventTarget = AopTestUtils.getUltimateTargetObject(events);
        doThrow(new IllegalStateException("event insert failed"))
                .doCallRealMethod().when(eventTarget).append(OWNER, created.getId(), "read");
        assertThatThrownBy(() -> notifications.markRead(OWNER, created.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessage("event insert failed");
        assertThat(jdbc.queryForObject("SELECT read_flag FROM notifications WHERE id=?", Boolean.class, created.getId())).isFalse();
        assertThat(events.current(OWNER)).isEqualTo(1);
        assertThat(readEvents()).isZero();

        assertThat(notifications.markRead(OWNER, created.getId()).isRead()).isTrue();
        assertThat(readEvents()).isEqualTo(1);
        assertThat(events.current(OWNER)).isEqualTo(2);
    }

    private NotificationDto create() {
        var request = new CreateNotificationRequest();
        request.setEventId(UUID.randomUUID());
        request.setUserId(OWNER);
        request.setType("reply");
        request.setTitle("Title");
        request.setContent("Content");
        request.setThreadId(7L);
        request.setTargetId(8L);
        request.setTargetType("post");
        return notifications.create(request);
    }

    private long readEvents() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notification_stream_event WHERE user_id=? AND event_kind='read'",
                Long.class, OWNER);
    }

    private long readUpdateCalls() {
        return mockingDetails(jdbc).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("update"))
                .filter(invocation -> invocation.getMethod().isVarArgs())
                .filter(invocation -> invocation.getArguments().length > 0
                        && invocation.getArgument(0) instanceof String sql
                        && sql.startsWith("UPDATE notifications SET read_flag"))
                .count();
    }
}
