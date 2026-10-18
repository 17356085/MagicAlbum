package com.example.demo.ai.controller;

import com.example.demo.ai.service.AiService;
import com.example.demo.ai.service.AiQuotaService;
import com.example.demo.ai.service.AiSummaryCacheService;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiControllerTest {

    @Test
    void getSummary_should_ignore_stale_cached_task_state() {
        AiService aiService = mock(AiService.class);
        AiSummaryCacheService cacheService = mock(AiSummaryCacheService.class);
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(null);
        Thread thread = thread(9L, "NORMAL");
        when(threadRepository.findById(9L)).thenReturn(Optional.of(thread));

        AiController controller = controller(aiService, cacheService, threadRepository, rabbitProvider,
                false, mock(AiQuotaService.class));
        Map<String, Object> response = controller.getSummary(9L);

        assertThat(response.get("summary")).isEqualTo("");
        assertThat(response.get("status")).isEqualTo("PENDING");
        verify(threadRepository).findById(9L);
    }

    @Test
    void getSummary_should_read_database_and_fill_cache_on_miss() {
        AiSummaryCacheService cacheService = mock(AiSummaryCacheService.class);
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(null);
        Thread thread = thread(9L, "NORMAL");
        thread.setSummary("from database");
        thread.setSummaryStatus("COMPLETED");
        when(threadRepository.findById(9L)).thenReturn(Optional.of(thread));

        AiController controller = controller(mock(AiService.class), cacheService, threadRepository,
                rabbitProvider, false, mock(AiQuotaService.class));

        Map<String, Object> response = controller.getSummary(9L);

        assertThat(response).containsEntry("summary", "from database").containsEntry("status", "COMPLETED");
        verify(cacheService).put(9L, "from database", "COMPLETED");
    }

    @Test
    void getSummary_should_evict_cache_and_hide_non_normal_threads() {
        for (String status : new String[]{"HIDDEN", "DELETED"}) {
            AiSummaryCacheService cacheService = mock(AiSummaryCacheService.class);
            ThreadRepository threadRepository = mock(ThreadRepository.class);
            ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
            when(rabbitProvider.getIfAvailable()).thenReturn(null);
            when(threadRepository.findById(9L)).thenReturn(Optional.of(thread(9L, status)));

            AiController controller = controller(mock(AiService.class), cacheService, threadRepository,
                    rabbitProvider, false, mock(AiQuotaService.class));

            assertThatThrownBy(() -> controller.getSummary(9L))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
            verify(cacheService).evict(9L);
        }
    }

    @Test
    void triggerSummary_should_reject_when_summary_is_processing() {
        AiSummaryCacheService cacheService = mock(AiSummaryCacheService.class);
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(mock(RabbitTemplate.class));
        Thread thread = thread(5L, "NORMAL");
        thread.setSummaryStatus("PROCESSING");
        when(threadRepository.findById(5L)).thenReturn(Optional.of(thread));

        AiController controller = controller(mock(AiService.class), cacheService, threadRepository,
                rabbitProvider, true, mock(AiQuotaService.class));

        assertThatThrownBy(() -> controller.triggerSummary(5L, true, 7L, request()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        verify(threadRepository, never()).updateSummaryStatusIfNormal(5L, "PENDING");
        verify(cacheService, never()).put(eq(5L), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void triggerSummary_should_report_conflict_when_claim_races_with_processing_consumer() {
        AiSummaryCacheService cacheService = mock(AiSummaryCacheService.class);
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(mock(RabbitTemplate.class));
        Thread initial = thread(5L, "NORMAL");
        initial.setSummary("old");
        initial.setSummaryStatus("COMPLETED");
        Thread processing = thread(5L, "NORMAL");
        processing.setSummary("old");
        processing.setSummaryStatus("PROCESSING");
        when(threadRepository.findById(5L)).thenReturn(Optional.of(initial), Optional.of(processing));
        when(threadRepository.updateSummaryStatusIfNormal(5L, "PENDING")).thenReturn(0);

        AiController controller = controller(mock(AiService.class), cacheService, threadRepository,
                rabbitProvider, true, mock(AiQuotaService.class));

        assertThatThrownBy(() -> controller.triggerSummary(5L, true, 7L, request()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        verify(cacheService, never()).evict(5L);
        verify(cacheService, never()).put(eq(5L), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void triggerSummary_should_not_mark_pending_when_queue_is_unavailable() {
        AiService aiService = mock(AiService.class);
        AiSummaryCacheService cacheService = mock(AiSummaryCacheService.class);
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(null);
        AiQuotaService quotaService = mock(AiQuotaService.class);

        Thread thread = new Thread();
        thread.setId(5L);
        thread.setStatus("NORMAL");
        thread.setSummary("old");
        thread.setSummaryStatus("COMPLETED");
        when(threadRepository.findById(5L)).thenReturn(Optional.of(thread));

        AiController controller = controller(aiService, cacheService, threadRepository, rabbitProvider,
                false, quotaService);

        assertThatThrownBy(() -> controller.triggerSummary(5L, true, 7L, request()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(quotaService).enforceRequest(7L, "127.0.0.1");
        verify(threadRepository, never()).save(thread);
        verify(cacheService, never()).put(eq(5L), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void triggerSummary_should_evict_and_reject_hidden_or_deleted_threads() {
        for (String status : new String[]{"HIDDEN", "DELETED"}) {
            AiSummaryCacheService cacheService = mock(AiSummaryCacheService.class);
            ThreadRepository threadRepository = mock(ThreadRepository.class);
            ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
            when(rabbitProvider.getIfAvailable()).thenReturn(mock(RabbitTemplate.class));
            when(threadRepository.findById(5L)).thenReturn(Optional.of(thread(5L, status)));

            AiController controller = controller(mock(AiService.class), cacheService, threadRepository,
                    rabbitProvider, true, mock(AiQuotaService.class));

            assertThatThrownBy(() -> controller.triggerSummary(5L, true, 7L, request()))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
            verify(cacheService).evict(5L);
            verify(threadRepository, never()).updateSummaryStatusIfNormal(5L, "PENDING");
        }
    }

    @Test
    void triggerSummary_should_enqueue_and_write_pending_cache() {
        AiService aiService = mock(AiService.class);
        AiSummaryCacheService cacheService = mock(AiSummaryCacheService.class);
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(rabbitTemplate);
        AiQuotaService quotaService = mock(AiQuotaService.class);

        Thread thread = new Thread();
        thread.setId(5L);
        thread.setStatus("NORMAL");
        thread.setSummary("old");
        thread.setSummaryStatus("COMPLETED");
        when(threadRepository.findById(5L)).thenReturn(Optional.of(thread));
        when(threadRepository.updateSummaryStatusIfNormal(5L, "PENDING")).thenReturn(1);

        AiController controller = controller(aiService, cacheService, threadRepository, rabbitProvider,
                true, quotaService);
        Map<String, Object> response = controller.triggerSummary(5L, true, 7L, request());

        assertThat(response.get("status")).isEqualTo("PENDING");
        verify(threadRepository).updateSummaryStatusIfNormal(5L, "PENDING");
        verify(cacheService).put(5L, "old", "PENDING");
        verify(rabbitTemplate).convertAndSend(
                com.example.demo.common.config.RabbitMQConfig.QUEUE_THREAD_SUMMARY, 5L);
    }

    @Test
    void triggerSummary_should_keep_durable_intent_when_enqueue_fails() {
        AiService aiService = mock(AiService.class);
        AiSummaryCacheService cacheService = mock(AiSummaryCacheService.class);
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(rabbitTemplate);
        org.mockito.Mockito.doThrow(new IllegalStateException("broker unavailable"))
                .when(rabbitTemplate).convertAndSend(
                        com.example.demo.common.config.RabbitMQConfig.QUEUE_THREAD_SUMMARY, 5L);

        Thread thread = new Thread();
        thread.setId(5L);
        thread.setStatus("NORMAL");
        thread.setSummary("old");
        thread.setSummaryStatus("COMPLETED");
        when(threadRepository.findById(5L)).thenReturn(Optional.of(thread));
        when(threadRepository.updateSummaryStatusIfNormal(5L, "PENDING")).thenReturn(1);

        AiController controller = controller(aiService, cacheService, threadRepository, rabbitProvider,
                true, mock(AiQuotaService.class));

        assertThatThrownBy(() -> controller.triggerSummary(5L, true, 7L, request()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(threadRepository).updateSummaryStatusIfNormal(5L, "PENDING");
        verify(threadRepository, never()).restoreSummaryIfPendingAndNormal(5L, "old", "COMPLETED");
        verify(cacheService).put(5L, "old", "PENDING");
        verify(cacheService).evict(5L);
    }

    @SuppressWarnings("unchecked")
    private AiController controller(
            AiService aiService,
            AiSummaryCacheService cacheService,
            ThreadRepository threadRepository,
            ObjectProvider<RabbitTemplate> rabbitProvider,
            boolean rabbitEnabled,
            AiQuotaService quotaService
    ) {
        return new AiController(
                aiService,
                cacheService,
                threadRepository,
                rabbitProvider,
                rabbitEnabled,
                quotaService,
                30,
                12_000
        );
    }

    private HttpServletRequest request() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        return request;
    }

    private Thread thread(Long id, String status) {
        Thread thread = new Thread();
        thread.setId(id);
        thread.setStatus(status);
        return thread;
    }
}
