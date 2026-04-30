package com.example.demo.ai.controller;

import com.example.demo.ai.service.AiService;
import com.example.demo.ai.service.AiSummaryCacheService;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

        AiController controller = new AiController(aiService, cacheService, threadRepository, rabbitProvider, false);
        Map<String, Object> response = controller.getSummary(9L);

        assertThat(response.get("summary")).isEqualTo("cached");
        assertThat(response.get("status")).isEqualTo("COMPLETED");
        verify(threadRepository, never()).findById(anyLong());
    }

    @Test
    void triggerSummary_should_write_pending_cache() {
        AiService aiService = mock(AiService.class);
        AiSummaryCacheService cacheService = mock(AiSummaryCacheService.class);
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(null);

        Thread thread = new Thread();
        thread.setId(5L);
        thread.setSummary("old");
        thread.setSummaryStatus("COMPLETED");
        when(threadRepository.findById(5L)).thenReturn(Optional.of(thread));
        when(threadRepository.save(thread)).thenReturn(thread);

        AiController controller = new AiController(aiService, cacheService, threadRepository, rabbitProvider, false);
        Map<String, Object> response = controller.triggerSummary(5L, true);

        assertThat(response.get("status")).isEqualTo("PENDING");
        verify(cacheService).put(5L, "old", "PENDING");
    }
}
