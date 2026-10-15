package com.example.demo.notifications.outbox;

import com.example.demo.user.service.UserSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationOutboxServiceBehaviorTest {
    private final NotificationOutboxRepository repository = mock(NotificationOutboxRepository.class);
    private final UserSettingsService settings = mock(UserSettingsService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void max_attempts_transitions_to_failed_and_manual_retry_starts_a_new_cycle() {
        NotificationOutboxService service = service(3);
        NotificationOutboxService.Delivery delivery = new NotificationOutboxService.Delivery(
                9L, "event-9", 101L, "reply", "Title", "Content", null, null, "post", 3);
        when(repository.finishFailure(
                eq(9L),
                eq(NotificationOutboxStatus.IN_FLIGHT),
                eq(NotificationOutboxStatus.FAILED),
                eq(clock.instant()),
                eq("HTTP_500"),
                eq("owner-a"),
                eq(clock.instant())
        )).thenReturn(1);
        when(repository.retryFailed(
                eq(9L),
                eq(NotificationOutboxStatus.FAILED),
                eq(NotificationOutboxStatus.PENDING),
                eq(clock.instant())
        )).thenReturn(1);

        assertThat(service.markFailedOrRetry(
                delivery,
                "owner-a",
                new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "secret response body")
        )).isTrue();
        assertThat(service.manualRetry(9L)).isTrue();
    }

    @Test
    void permanent_client_failure_is_failed_immediately_and_only_a_safe_code_is_persisted() {
        NotificationOutboxService service = service(5);
        NotificationOutboxService.Delivery delivery = new NotificationOutboxService.Delivery(
                10L, "event-10", 101L, "reply", "Title", "Content", null, null, "post", 1);
        when(repository.finishFailure(
                eq(10L),
                eq(NotificationOutboxStatus.IN_FLIGHT),
                eq(NotificationOutboxStatus.FAILED),
                eq(clock.instant()),
                eq("HTTP_409"),
                eq("owner-a"),
                eq(clock.instant())
        )).thenReturn(1);

        assertThat(service.markFailedOrRetry(
                delivery,
                "owner-a",
                new ResponseStatusException(HttpStatus.CONFLICT, "notification content must not persist")
        )).isTrue();
    }

    @Test
    void production_lease_must_exceed_the_remote_request_budget() {
        assertThatThrownBy(() -> new NotificationOutboxService(
                repository,
                settings,
                Duration.ofSeconds(20),
                10,
                3,
                Duration.ofSeconds(1),
                Duration.ofDays(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationOutboxService(repository, settings,
                Duration.ofSeconds(29), 10, 3, Duration.ofSeconds(1), Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new NotificationOutboxService(repository, settings, Duration.ofSeconds(30),
                10, 3, Duration.ofSeconds(1), Duration.ofDays(1))).isNotNull();
    }

    private NotificationOutboxService service(int maxAttempts) {
        return new NotificationOutboxService(
                repository,
                settings,
                clock,
                Duration.ofSeconds(21),
                10,
                maxAttempts,
                Duration.ofSeconds(1),
                Duration.ofDays(1)
        );
    }
}
