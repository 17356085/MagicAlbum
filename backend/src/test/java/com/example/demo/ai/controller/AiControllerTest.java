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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class AiControllerTest {

    @Test
    void getSummary_should_return_cached_payload_without_querying_repository() {
        AiService aiService = mock(AiService.class);
        AiSummaryCacheService cacheService = mock(AiSummaryCacheService.class);
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(null);
        when(cacheService.get(9L)).thenReturn(new AiSummaryCacheService.CachedAiSummary("cached", "COMPLETED"));

        AiController controller = controller(aiService, cacheService, threadRepository, rabbitProvider,
                false, mock(AiQuotaService.class));
        Map<String, Object> response = controller.getSummary(9L);

        assertThat(response.get("summary")).isEqualTo("cached");
        assertThat(response.get("status")).isEqualTo("COMPLETED");
        verify(threadRepository, never()).findById(anyLong());
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
        thread.setSummary("old");
        thread.setSummaryStatus("COMPLETED");
        when(threadRepository.findById(5L)).thenReturn(Optional.of(thread));
        when(threadRepository.save(thread)).thenReturn(thread);

        AiController controller = controller(aiService, cacheService, threadRepository, rabbitProvider,
                true, quotaService);
        Map<String, Object> response = controller.triggerSummary(5L, true, 7L, request());

        assertThat(response.get("status")).isEqualTo("PENDING");
        verify(threadRepository).save(thread);
        verify(cacheService).put(5L, "old", "PENDING");
        verify(rabbitTemplate).convertAndSend(
                com.example.demo.common.config.RabbitMQConfig.QUEUE_THREAD_SUMMARY, 5L);
    }

    @Test
    void triggerSummary_should_restore_previous_state_when_enqueue_fails() {
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
        thread.setSummary("old");
        thread.setSummaryStatus("COMPLETED");
        when(threadRepository.findById(5L)).thenReturn(Optional.of(thread));
        when(threadRepository.save(thread)).thenReturn(thread);

        AiController controller = controller(aiService, cacheService, threadRepository, rabbitProvider,
                true, mock(AiQuotaService.class));

        assertThatThrownBy(() -> controller.triggerSummary(5L, true, 7L, request()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(thread.getSummary()).isEqualTo("old");
        assertThat(thread.getSummaryStatus()).isEqualTo("COMPLETED");
        verify(threadRepository, times(2)).save(thread);
        verify(cacheService).put(5L, "old", "PENDING");
        verify(cacheService).put(5L, "old", "COMPLETED");
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
}
