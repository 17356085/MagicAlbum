package com.example.demo.ai.summary;

import com.example.demo.ai.summary.repo.SummaryTaskRepository;
import com.example.demo.ai.summary.service.SummaryTaskPublisher;
import com.example.demo.ai.summary.service.SummaryTaskRecovery;
import java.util.List;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SummaryTaskRecoveryTest {
    @Test
    void aFiniteScanKeepsItsCutoffThenIncludesNewLowIdsInTheNextScan() {
        var tasks = mock(SummaryTaskRepository.class);
        var publisher = mock(SummaryTaskPublisher.class);
        var clock = mock(Clock.class);
        Instant first = Instant.parse("2026-10-20T00:00:00Z");
        Instant next = first.plusSeconds(600);
        when(clock.instant()).thenReturn(first, next);
        List<String> page = java.util.stream.IntStream.range(20, 40)
                .mapToObj(n -> String.format("00000000-0000-4000-8000-%012d", n)).toList();
        String low = "00000000-0000-4000-8000-000000000001";
        when(tasks.recoverable(anyString(), any(), any(), any())).thenReturn(page, List.of(), List.of(low));
        when(publisher.publish(anyString())).thenReturn(true);
        var recovery = new SummaryTaskRecovery(tasks, publisher, true, clock);
        recovery.recover();
        recovery.recover();
        recovery.recover();
        var timestamps = ArgumentCaptor.forClass(Instant.class);
        var before = ArgumentCaptor.forClass(Instant.class);
        var cursors = ArgumentCaptor.forClass(String.class);
        verify(tasks, times(3)).recoverable(cursors.capture(), timestamps.capture(), before.capture(), any());
        assertThat(cursors.getAllValues()).containsExactly("", page.getLast(), "");
        assertThat(timestamps.getAllValues()).containsExactly(first, first, next);
        assertThat(before.getAllValues()).containsExactly(first.minusSeconds(30), first.minusSeconds(30), next.minusSeconds(30));
        verify(publisher).publish(low);
        verify(clock, times(2)).instant();
    }

    @Test
    void disabledRecoveryDoesNotReadOrPublish() {
        var tasks = mock(SummaryTaskRepository.class);
        var publisher = mock(SummaryTaskPublisher.class);
        new SummaryTaskRecovery(tasks, publisher, false).recover();
        verifyNoInteractions(tasks, publisher);
    }

    @Test
    void failedDeliveryIsRetriedBeforeCursorCanAdvancePastIt() {
        var tasks = mock(SummaryTaskRepository.class);
        var publisher = mock(SummaryTaskPublisher.class);
        List<String> ids = java.util.stream.IntStream.range(1, 21)
                .mapToObj(n -> String.format("00000000-0000-4000-8000-%012d", n)).toList();
        when(tasks.recoverable(anyString(), any(), any(), any())).thenReturn(ids, ids.subList(2, 20));
        when(publisher.publish(anyString())).thenReturn(true);
        when(publisher.publish(ids.get(2))).thenReturn(false, true);
        var recovery = new SummaryTaskRecovery(tasks, publisher, true);
        recovery.recover();
        verify(publisher, never()).publish(ids.get(3));
        recovery.recover();
        var cursors = ArgumentCaptor.forClass(String.class);
        verify(tasks, times(2)).recoverable(cursors.capture(), any(), any(), any());
        assertThat(cursors.getAllValues()).containsExactly("", ids.get(1));
        verify(publisher, times(2)).publish(ids.get(2));
        verify(publisher).publish(ids.get(19));
    }
}
