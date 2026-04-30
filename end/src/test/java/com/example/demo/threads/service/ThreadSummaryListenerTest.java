package com.example.demo.threads.service;

import com.example.demo.ai.service.AiService;
import com.example.demo.ai.service.AiSummaryCacheService;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ThreadSummaryListenerTest {

    @Test
    void processThreadSummary_should_update_cache_during_lifecycle() {
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        AiService aiService = mock(AiService.class);
        AiSummaryCacheService cacheService = mock(AiSummaryCacheService.class);

        Thread thread = new Thread();
        thread.setId(11L);
        thread.setContentMd("content");
        thread.setSummaryStatus("PENDING");
        when(threadRepository.findById(11L)).thenReturn(Optional.of(thread));
        when(threadRepository.save(thread)).thenReturn(thread);
        when(aiService.generateSummary("content")).thenReturn("generated summary");

        ThreadSummaryListener listener = new ThreadSummaryListener(threadRepository, aiService, cacheService);
        listener.processThreadSummary(11L);

        verify(cacheService).put(11L, null, "PROCESSING");
        verify(cacheService).put(11L, "generated summary", "COMPLETED");
        verify(threadRepository, times(2)).save(thread);
    }
}
