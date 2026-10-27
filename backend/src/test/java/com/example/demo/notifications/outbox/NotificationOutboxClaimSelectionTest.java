package com.example.demo.notifications.outbox;

import com.example.demo.user.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationOutboxClaimSelectionTest {
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration LEASE = Duration.ofSeconds(30);

    private NotificationOutboxRepository repository;
    private UserSettingsService settings;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationOutboxRepository.class);
        settings = mock(UserSettingsService.class);
    }

    @Test
    void claimOne_uses_page_zero_with_requested_page_size_one_for_each_status() {
        NotificationOutbox pending = row(1L, NotificationOutboxStatus.PENDING, NOW.minusSeconds(2), 4);
        NotificationOutbox expired = row(2L, NotificationOutboxStatus.IN_FLIGHT, NOW.minusSeconds(1), 5);
        stubInitial(List.of(pending), List.of(expired), 1);
        stubClaimsAlwaysSucceed("owner");

        NotificationOutboxService.Delivery delivery = service(10).claimOne("owner").orElseThrow();

        assertThat(delivery.id()).isEqualTo(1L);
        assertThat(delivery.attempts()).isEqualTo(5);
        verify(repository).claim(eq(1L), eq(NotificationOutboxStatus.PENDING),
                eq(NotificationOutboxStatus.IN_FLIGHT), eq(NOW), eq("owner"), eq(NOW.plus(LEASE)), eq(4));
        verifyReadPage(1, 1);
        verifyReadPage(100, 0);
    }

    @Test
    void lost_oldest_pending_head_widens_once_and_preserves_global_order() {
        NotificationOutbox oldestPending = row(1L, NotificationOutboxStatus.PENDING, NOW.minusSeconds(10), 0);
        NotificationOutbox newerPending = row(3L, NotificationOutboxStatus.PENDING, NOW.minusSeconds(5), 0);
        NotificationOutbox newerExpired = row(2L, NotificationOutboxStatus.IN_FLIGHT, NOW.minusSeconds(1), 0);
        stubInitial(List.of(oldestPending), List.of(newerExpired), 1);
        stubFallback(List.of(oldestPending, newerPending), List.of(newerExpired));
        stubClaims(Map.of(1L, 0, 3L, 1, 2L, 1), "owner");

        NotificationOutboxService.Delivery delivery = service(10).claimOne("owner").orElseThrow();

        assertThat(delivery.id()).isEqualTo(3L);
        verifyReadPage(1, 1);
        verifyReadPage(100, 1);
        verifyClaimIds("owner", 1L, 3L);
        verify(repository, never()).claim(
                eq(2L), eq(NotificationOutboxStatus.PENDING), eq(NotificationOutboxStatus.IN_FLIGHT),
                eq(NOW), eq("owner"), eq(NOW.plus(LEASE)), anyInt());
    }

    @Test
    void fallback_skips_successful_and_failed_ids_from_the_first_round() {
        NotificationOutbox first = row(1L, NotificationOutboxStatus.PENDING, NOW.minusSeconds(10), 0);
        NotificationOutbox second = row(2L, NotificationOutboxStatus.PENDING, NOW.minusSeconds(5), 0);
        NotificationOutbox third = row(3L, NotificationOutboxStatus.IN_FLIGHT, NOW.minusSeconds(8), 0);
        NotificationOutbox fallbackPending = row(4L, NotificationOutboxStatus.PENDING, NOW.minusSeconds(1), 0);
        NotificationOutbox fallbackExpired = row(5L, NotificationOutboxStatus.IN_FLIGHT, NOW, 0);
        stubInitial(List.of(first, second), List.of(third), 3);
        stubFallback(List.of(first, second, fallbackPending), List.of(third, fallbackExpired));
        stubClaims(Map.of(1L, 1, 2L, 0, 3L, 1, 4L, 1), "owner");

        List<NotificationOutboxService.Delivery> deliveries = service(3).claimBatch("owner");

        assertThat(deliveries).extracting(NotificationOutboxService.Delivery::id)
                .containsExactly(1L, 3L, 4L);
        verifyReadPage(3, 1);
        verifyReadPage(100, 1);
        verifyClaimIds("owner", 1L, 3L, 2L, 4L);
        verify(repository, never()).claim(
                eq(5L), eq(NotificationOutboxStatus.PENDING), eq(NotificationOutboxStatus.IN_FLIGHT),
                eq(NOW), eq("owner"), eq(NOW.plus(LEASE)), anyInt());
    }

    @Test
    void all_lost_candidates_stop_after_the_single_bounded_fallback() {
        NotificationOutbox first = row(1L, NotificationOutboxStatus.PENDING, NOW.minusSeconds(2), 0);
        NotificationOutbox fallback = row(2L, NotificationOutboxStatus.PENDING, NOW.minusSeconds(1), 0);
        stubInitial(List.of(first), List.of(), 1);
        stubFallback(List.of(first, fallback), List.of());
        stubClaims(Map.of(1L, 0, 2L, 0), "owner");

        assertThat(service(10).claimOne("owner")).isEmpty();

        verifyReadPage(1, 1);
        verifyReadPage(100, 1);
        verifyClaimIds("owner", 1L, 2L);
    }

    @Test
    void short_batch_without_contention_does_not_widen_the_candidate_pages() {
        NotificationOutbox only = row(1L, NotificationOutboxStatus.PENDING, NOW.minusSeconds(2), 0);
        stubInitial(List.of(only), List.of(), 3);
        stubClaimsAlwaysSucceed("owner");

        List<NotificationOutboxService.Delivery> deliveries = service(3).claimBatch("owner");

        assertThat(deliveries).extracting(NotificationOutboxService.Delivery::id).containsExactly(1L);
        verifyReadPage(3, 1);
        verifyReadPage(100, 0);
    }

    @Test
    void empty_candidate_pages_return_without_a_fallback_read() {
        stubInitial(List.of(), List.of(), 1);

        assertThat(service(10).claimOne("owner")).isEmpty();

        verifyReadPage(1, 1);
        verifyReadPage(100, 0);
    }

    @Test
    void full_initial_window_does_not_start_a_third_read_round_after_contention() {
        NotificationOutbox lost = row(1L, NotificationOutboxStatus.PENDING, NOW.minusSeconds(2), 0);
        NotificationOutbox recovered = row(2L, NotificationOutboxStatus.PENDING, NOW.minusSeconds(1), 0);
        stubInitial(List.of(lost, recovered), List.of(), 100);
        stubClaims(Map.of(1L, 0, 2L, 1), "owner");

        List<NotificationOutboxService.Delivery> deliveries = service(100).claimBatch("owner");

        assertThat(deliveries).extracting(NotificationOutboxService.Delivery::id).containsExactly(2L);
        verifyReadPage(100, 1);
        verifyClaimIds("owner", 1L, 2L);
    }

    @Test
    void pending_and_expired_candidates_merge_by_due_timestamp_then_id() {
        NotificationOutbox pendingTie = row(10L, NotificationOutboxStatus.PENDING, NOW.minusSeconds(10), 0);
        NotificationOutbox pendingLater = row(2L, NotificationOutboxStatus.PENDING, NOW.minusSeconds(5), 0);
        NotificationOutbox expiredTie = row(5L, NotificationOutboxStatus.IN_FLIGHT, NOW.minusSeconds(10), 0);
        NotificationOutbox expiredLater = row(20L, NotificationOutboxStatus.IN_FLIGHT, NOW.minusSeconds(5), 0);
        stubInitial(List.of(pendingTie, pendingLater), List.of(expiredTie, expiredLater), 4);
        stubClaimsAlwaysSucceed("owner");

        List<NotificationOutboxService.Delivery> deliveries = service(4).claimBatch("owner");

        assertThat(deliveries).extracting(NotificationOutboxService.Delivery::id)
                .containsExactly(5L, 10L, 2L, 20L);
        verifyReadPage(4, 1);
        verifyReadPage(100, 0);
    }

    @Test
    void batch_result_is_limited_and_snapshots_attempts_before_claim_update() {
        NotificationOutbox first = row(1L, NotificationOutboxStatus.PENDING, NOW.minusSeconds(3), 4);
        NotificationOutbox second = row(2L, NotificationOutboxStatus.PENDING, NOW.minusSeconds(2), 7);
        NotificationOutbox third = row(3L, NotificationOutboxStatus.PENDING, NOW.minusSeconds(1), 0);
        NotificationOutbox extra = row(4L, NotificationOutboxStatus.PENDING, NOW, 2);
        stubInitial(List.of(first, second, third, extra), List.of(), 3);
        stubClaimsAlwaysSucceed("owner");

        List<NotificationOutboxService.Delivery> deliveries = service(3).claimBatch("owner");

        assertThat(deliveries).extracting(NotificationOutboxService.Delivery::id)
                .containsExactly(1L, 2L, 3L);
        assertThat(deliveries).extracting(NotificationOutboxService.Delivery::attempts)
                .containsExactly(5, 8, 1);
        verifyReadPage(3, 1);
        verifyReadPage(100, 0);
        verify(repository, never()).claim(
                eq(4L), eq(NotificationOutboxStatus.PENDING), eq(NotificationOutboxStatus.IN_FLIGHT),
                eq(NOW), eq("owner"), eq(NOW.plus(LEASE)), anyInt());
    }

    private NotificationOutboxService service(int batchSize) {
        return new NotificationOutboxService(
                repository,
                settings,
                CLOCK,
                LEASE,
                batchSize,
                5,
                Duration.ofSeconds(1),
                Duration.ofDays(1)
        );
    }

    private void stubInitial(
            List<NotificationOutbox> pending,
            List<NotificationOutbox> expired,
            int pageSize
    ) {
        stubRead(NotificationOutboxStatus.PENDING, pending, pageSize);
        stubRead(NotificationOutboxStatus.IN_FLIGHT, expired, pageSize);
    }

    private void stubFallback(List<NotificationOutbox> pending, List<NotificationOutbox> expired) {
        stubRead(NotificationOutboxStatus.PENDING, pending, 100);
        stubRead(NotificationOutboxStatus.IN_FLIGHT, expired, 100);
    }

    private void stubRead(NotificationOutboxStatus status, List<NotificationOutbox> rows, int pageSize) {
        if (status == NotificationOutboxStatus.PENDING) {
            when(repository.findByStatusAndAvailableAtLessThanEqualOrderByAvailableAtAscIdAsc(
                    eq(status), eq(NOW), eq(PageRequest.of(0, pageSize)))).thenReturn(rows);
        } else {
            when(repository.findByStatusAndLeaseUntilLessThanOrderByLeaseUntilAscIdAsc(
                    eq(status), eq(NOW), eq(PageRequest.of(0, pageSize)))).thenReturn(rows);
        }
    }

    private void stubClaimsAlwaysSucceed(String owner) {
        when(repository.claim(
                anyLong(),
                eq(NotificationOutboxStatus.PENDING),
                eq(NotificationOutboxStatus.IN_FLIGHT),
                eq(NOW),
                eq(owner),
                eq(NOW.plus(LEASE)), anyInt()
        )).thenReturn(1);
    }

    private void stubClaims(Map<Long, Integer> outcomes, String owner) {
        when(repository.claim(
                anyLong(),
                eq(NotificationOutboxStatus.PENDING),
                eq(NotificationOutboxStatus.IN_FLIGHT),
                eq(NOW),
                eq(owner),
                eq(NOW.plus(LEASE)), anyInt()
        )).thenAnswer(invocation -> outcomes.getOrDefault(invocation.getArgument(0), 0));
    }

    private void verifyReadPage(int pageSize, int count) {
        Pageable page = PageRequest.of(0, pageSize);
        verify(repository, times(count)).findByStatusAndAvailableAtLessThanEqualOrderByAvailableAtAscIdAsc(
                eq(NotificationOutboxStatus.PENDING), eq(NOW), eq(page));
        verify(repository, times(count)).findByStatusAndLeaseUntilLessThanOrderByLeaseUntilAscIdAsc(
                eq(NotificationOutboxStatus.IN_FLIGHT), eq(NOW), eq(page));
    }

    private void verifyClaimIds(String owner, Long... ids) {
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(repository, times(ids.length)).claim(
                captor.capture(),
                eq(NotificationOutboxStatus.PENDING),
                eq(NotificationOutboxStatus.IN_FLIGHT),
                eq(NOW),
                eq(owner),
                eq(NOW.plus(LEASE)), anyInt()
        );
        assertThat(captor.getAllValues()).containsExactly(ids);
    }

    private static NotificationOutbox row(
            Long id,
            NotificationOutboxStatus status,
            Instant dueAt,
            int attempts
    ) {
        NotificationOutbox row = new NotificationOutbox();
        row.setId(id);
        row.setEventId("event-" + id);
        row.setRecipientUserId(101L);
        row.setType("reply");
        row.setTitle("Title");
        row.setContent("Content");
        row.setStatus(status);
        row.setAttempts(attempts);
        row.setAvailableAt(dueAt);
        row.setLeaseUntil(status == NotificationOutboxStatus.IN_FLIGHT ? dueAt : null);
        row.setCreatedAt(dueAt);
        row.setUpdatedAt(dueAt);
        return row;
    }
}
