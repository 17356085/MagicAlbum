package com.example.demo.ai.summary;

import com.example.demo.ai.summary.repo.SummaryTaskRepository;
import com.example.demo.ai.summary.service.SummaryTaskPublisher;
import com.example.demo.ai.summary.service.SummaryTaskRecovery;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SummaryTaskRecoveryTest {
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
