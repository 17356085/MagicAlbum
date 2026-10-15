package com.example.notification.service;

import com.example.notification.dto.CreateNotificationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.aot.DisabledInAotMode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisabledInAotMode
class NotificationIdempotencyConcurrencyTest {
    @Autowired
    private PersistentNotificationService notificationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanNotifications() {
        jdbcTemplate.update("DELETE FROM notifications");
    }

    @Test
    void concurrent_same_event_id_creates_one_row_and_all_callers_read_the_winner() throws Exception {
        UUID eventId = UUID.randomUUID();
        ExecutorService workers = Executors.newFixedThreadPool(6);
        try {
            CreateNotificationRequest request = request(eventId, "Title");
            List<Callable<Long>> calls = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                calls.add(() -> notificationService.create(request).getId());
            }

            List<Future<Long>> results = workers.invokeAll(calls, 10, TimeUnit.SECONDS);
            assertThat(results).allMatch(result -> !result.isCancelled());
            List<Long> ids = new ArrayList<>();
            for (Future<Long> result : results) {
                ids.add(result.get());
            }

            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM notifications WHERE idempotency_key = ?",
                    Integer.class,
                    eventId.toString()
            );
            assertThat(count).isEqualTo(1);
            assertThat(ids).containsOnly(ids.get(0));
        } finally {
            workers.shutdownNow();
        }
    }

    private CreateNotificationRequest request(UUID eventId, String title) {
        CreateNotificationRequest request = new CreateNotificationRequest();
        request.setUserId(101L);
        request.setType("reply");
        request.setTitle(title);
        request.setContent("Content");
        request.setThreadId(7L);
        request.setTargetId(8L);
        request.setTargetType("post");
        request.setEventId(eventId);
        return request;
    }
}
