package com.example.demo.threads.event;

import com.example.demo.threads.event.config.ContentEventProperties;
import com.example.demo.threads.event.dto.ContentEventMessage;
import com.example.demo.threads.event.entity.ContentEventOutbox;
import com.example.demo.threads.event.entity.ContentEventStatus;
import com.example.demo.threads.event.entity.ContentEventType;
import com.example.demo.threads.event.repo.ContentEventOutboxRepository;
import com.example.demo.threads.event.service.ContentEventOutboxService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentEventOutboxServiceBehaviorTest {
    private final ContentEventOutboxRepository repository = mock(ContentEventOutboxRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void claim_increments_attempts_and_maps_only_fixed_event_metadata() {
        ContentEventOutbox row = pending(7L);
        when(repository.findTop20ByStatusAndAvailableAtLessThanEqualOrderByAvailableAtAscIdAsc(
                eq(ContentEventStatus.PENDING), eq(clock.instant()))).thenReturn(List.of(row));
        when(repository.findTop20ByStatusAndLeaseUntilLessThanOrderByLeaseUntilAscIdAsc(
                eq(ContentEventStatus.IN_FLIGHT), eq(clock.instant()))).thenReturn(List.of());
        when(repository.claim(eq(7L), eq(ContentEventStatus.PENDING), eq(ContentEventStatus.IN_FLIGHT),
                eq(clock.instant()), eq("worker-a"), eq(clock.instant().plusSeconds(30)))).thenReturn(1);

        ContentEventProperties properties = properties();
        ContentEventOutboxService service = new ContentEventOutboxService(repository, properties, clock);

        ContentEventOutboxService.Delivery delivery = service.claimOne("worker-a").orElseThrow();

        assertThat(delivery.attempts()).isEqualTo(1);
        assertThat(delivery.message().eventId()).isEqualTo("event-7");
        assertThat(delivery.message().eventType()).isEqualTo("THREAD_UPSERTED");
        assertThat(delivery.message().requestId()).isEqualTo("request-7");
    }

    @Test
    void failure_uses_safe_code_and_bounded_backoff_then_manual_retry_resets_attempts() {
        ContentEventProperties properties = properties();
        ContentEventOutboxService service = new ContentEventOutboxService(repository, properties, clock);
        ContentEventOutboxService.Delivery delivery = new ContentEventOutboxService.Delivery(
                9L,
                new ContentEventMessage(
                        "event-9", 1, "THREAD", 9L, 1L, "THREAD_UPSERTED", clock.instant(), "request-9"),
                1,
                "worker-a",
                clock.instant().plusSeconds(30));
        when(repository.markFailure(eq(9L), eq(ContentEventStatus.IN_FLIGHT),
                eq(ContentEventStatus.PENDING), eq(clock.instant().plusSeconds(2)),
                eq("CONFIRM_TIMEOUT"), eq("worker-a"), eq(clock.instant()))).thenReturn(1);

        assertThat(service.markFailure(delivery,
                new com.example.demo.threads.event.service.ContentEventPublisherTransport.PublishException(
                        com.example.demo.threads.event.service.ContentEventPublisherTransport.Code.CONFIRM_TIMEOUT)))
                .isTrue();

        when(repository.retryFailed(eq(9L), eq(ContentEventStatus.FAILED),
                eq(ContentEventStatus.PENDING), eq(clock.instant()))).thenReturn(1);
        assertThat(service.manualRetry(9L)).isTrue();
        verify(repository).retryFailed(9L, ContentEventStatus.FAILED,
                ContentEventStatus.PENDING, clock.instant());
    }

    @Test
    void properties_reject_a_lease_shorter_than_the_confirm_budget() {
        ContentEventProperties properties = properties();
        properties.setLeaseDuration(Duration.ofSeconds(11));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease-duration");
    }

    private ContentEventOutbox pending(Long id) {
        ContentEventOutbox row = new ContentEventOutbox();
        row.setId(id);
        row.setEventId("event-" + id);
        row.setSchemaVersion(1);
        row.setAggregateType("THREAD");
        row.setAggregateId(id);
        row.setAggregateVersion(1L);
        row.setEventType(ContentEventType.THREAD_UPSERTED);
        row.setOccurredAt(clock.instant());
        row.setRequestId("request-" + id);
        row.setStatus(ContentEventStatus.PENDING);
        row.setAttempts(0);
        row.setAvailableAt(clock.instant());
        return row;
    }

    private ContentEventProperties properties() {
        ContentEventProperties properties = new ContentEventProperties();
        properties.setLeaseDuration(Duration.ofSeconds(30));
        properties.setConfirmTimeout(Duration.ofSeconds(5));
        properties.setConnectionTimeout(Duration.ofSeconds(5));
        properties.setCommitMargin(Duration.ofSeconds(2));
        return properties;
    }
}
