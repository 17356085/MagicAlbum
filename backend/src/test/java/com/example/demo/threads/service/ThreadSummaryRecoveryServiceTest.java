package com.example.demo.threads.service;

import com.example.demo.common.config.RabbitMQConfig;
import com.example.demo.threads.repo.ThreadSummaryRecoveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ThreadSummaryRecoveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");
    private final ThreadSummaryRecoveryRepository repository = mock(ThreadSummaryRecoveryRepository.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);

    private ThreadSummaryRecoveryService service(int batchSize) {
        return new ThreadSummaryRecoveryService(repository, rabbit, Clock.fixed(NOW, ZoneOffset.UTC),
                batchSize, Duration.ofSeconds(60));
    }

    @Test
    void failedPublishRemainsEligibleOnNextPassAndDoesNotBlockOtherItems() {
        when(repository.findPendingIds(0, NOW.minusSeconds(60), NOW, PageRequest.of(0, 20)))
                .thenReturn(List.of(1L, 2L));
        doThrow(new IllegalStateException("broker down")).doNothing()
                .when(rabbit).convertAndSend(RabbitMQConfig.QUEUE_THREAD_SUMMARY, 1L);
        ThreadSummaryRecoveryService recovery = service(20);
        recovery.recoverPending();
        recovery.recoverPending();
        verify(rabbit, times(2)).convertAndSend(RabbitMQConfig.QUEUE_THREAD_SUMMARY, 1L);
        verify(rabbit, times(2)).convertAndSend(RabbitMQConfig.QUEUE_THREAD_SUMMARY, 2L);
    }

    @Test
    void keysetWalkVisitsLaterItemsThenWraps() {
        when(repository.findPendingIds(0, NOW.minusSeconds(60), NOW, PageRequest.of(0, 2)))
                .thenReturn(List.of(1L, 2L));
        when(repository.findPendingIds(2, NOW.minusSeconds(60), NOW, PageRequest.of(0, 2)))
                .thenReturn(List.of(3L));
        ThreadSummaryRecoveryService recovery = service(2);
        recovery.recoverPending();
        recovery.recoverPending();
        recovery.recoverPending();
        verify(repository, times(2)).findPendingIds(0, NOW.minusSeconds(60), NOW, PageRequest.of(0, 2));
        verify(rabbit).convertAndSend(RabbitMQConfig.QUEUE_THREAD_SUMMARY, 3L);
    }

    @Test
    void clampsBatchSizeAndRejectsAnAggressiveAgeWindow() {
        when(repository.findPendingIds(eq(0L), any(), any(), any())).thenReturn(List.of());
        service(1000).recoverPending();
        verify(repository).findPendingIds(0, NOW.minusSeconds(60), NOW, PageRequest.of(0, 100));
        assertThatThrownBy(() -> new ThreadSummaryRecoveryService(repository, rabbit, Clock.systemUTC(),
                20, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }
}
