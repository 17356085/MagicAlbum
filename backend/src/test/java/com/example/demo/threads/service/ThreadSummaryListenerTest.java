package com.example.demo.threads.service;

import com.example.demo.ai.service.AiService;
import com.example.demo.ai.service.AiSummaryCacheService;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
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
        thread.setStatus("NORMAL");
        thread.setContentMd("content");
        thread.setSummaryStatus("PENDING");
        when(threadRepository.findById(11L)).thenReturn(Optional.of(thread));
        when(threadRepository.claimSummaryProcessingIfNormal(eq(11L), anyString(), any(), any())).thenReturn(1);
        when(threadRepository.completeSummaryIfNormal(eq(11L), eq("generated summary"), anyString(), any(), isNull())).thenReturn(1);
        when(aiService.generateSummary("content")).thenReturn("generated summary");

        ThreadSummaryListener listener = new ThreadSummaryListener(threadRepository, aiService, cacheService);
        listener.processThreadSummary(11L);

        verify(cacheService).put(11L, null, "PROCESSING");
        verify(cacheService).put(11L, "generated summary", "COMPLETED");
        verify(threadRepository, never()).save(thread);
    }

    @Test
    void processThreadSummary_should_stop_when_thread_is_hidden_during_generation() {
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        AiService aiService = mock(AiService.class);
        AiSummaryCacheService cacheService = mock(AiSummaryCacheService.class);

        Thread thread = new Thread();
        thread.setId(11L);
        thread.setStatus("NORMAL");
        thread.setContentMd("content");
        thread.setSummaryStatus("PENDING");
        when(threadRepository.findById(11L)).thenReturn(Optional.of(thread));
        when(threadRepository.claimSummaryProcessingIfNormal(eq(11L), anyString(), any(), any())).thenReturn(1);
        when(aiService.generateSummary("content")).thenAnswer(invocation -> {
            thread.setStatus("HIDDEN");
            return "generated summary";
        });

        ThreadSummaryListener listener = new ThreadSummaryListener(threadRepository, aiService, cacheService);
        listener.processThreadSummary(11L);

        verify(cacheService).put(11L, null, "PROCESSING");
        verify(cacheService).evict(11L);
        verify(threadRepository, never()).completeSummaryIfNormal(eq(11L), eq("generated summary"), anyString(), any(), isNull());
        verify(threadRepository, never()).save(thread);
    }

    @Test
    void processThreadSummary_should_refuse_hidden_or_deleted_threads_before_claiming() {
        for (String status : new String[]{"HIDDEN", "DELETED"}) {
            ThreadRepository threadRepository = mock(ThreadRepository.class);
            AiService aiService = mock(AiService.class);
            AiSummaryCacheService cacheService = mock(AiSummaryCacheService.class);
            Thread thread = new Thread();
            thread.setId(11L);
            thread.setStatus(status);
            thread.setContentMd("content");
            thread.setSummaryStatus("PENDING");
            when(threadRepository.findById(11L)).thenReturn(Optional.of(thread));

            new ThreadSummaryListener(threadRepository, aiService, cacheService).processThreadSummary(11L);

            verify(threadRepository, never()).claimSummaryProcessingIfNormal(eq(11L), anyString(), any(), any());
            verify(aiService, never()).generateSummary("content");
            verify(cacheService).evict(11L);
        }
    }

    @Test
    void processThreadSummary_should_call_ai_only_once_when_duplicate_message_loses_claim() {
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        AiService aiService = mock(AiService.class);
        AiSummaryCacheService cacheService = mock(AiSummaryCacheService.class);
        Thread thread = new Thread();
        thread.setId(11L);
        thread.setStatus("NORMAL");
        thread.setContentMd("content");
        thread.setSummaryStatus("PENDING");
        when(threadRepository.findById(11L)).thenReturn(Optional.of(thread));
        when(threadRepository.claimSummaryProcessingIfNormal(eq(11L), anyString(), any(), any())).thenReturn(1, 0);
        when(threadRepository.completeSummaryIfNormal(eq(11L), eq("generated summary"), anyString(), any(), isNull())).thenReturn(1);
        when(aiService.generateSummary("content")).thenReturn("generated summary");

        ThreadSummaryListener listener = new ThreadSummaryListener(threadRepository, aiService, cacheService);
        listener.processThreadSummary(11L);
        listener.processThreadSummary(11L);

        verify(aiService, times(1)).generateSummary("content");
        verify(threadRepository, times(1)).completeSummaryIfNormal(eq(11L), eq("generated summary"), anyString(), any(), isNull());
        verify(cacheService).evict(11L);
    }

    @Test
    void processThreadSummary_should_not_cache_ai_result_when_delete_wins_before_completion() {
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        AiService aiService = mock(AiService.class);
        AiSummaryCacheService cacheService = mock(AiSummaryCacheService.class);
        Thread thread = new Thread();
        thread.setId(11L);
        thread.setStatus("NORMAL");
        thread.setContentMd("content");
        thread.setSummaryStatus("PENDING");
        when(threadRepository.findById(11L)).thenReturn(Optional.of(thread));
        when(threadRepository.claimSummaryProcessingIfNormal(eq(11L), anyString(), any(), any())).thenReturn(1);
        when(aiService.generateSummary("content")).thenReturn("generated summary");
        when(threadRepository.completeSummaryIfNormal(eq(11L), eq("generated summary"), anyString(), any(), isNull())).thenAnswer(invocation -> {
            thread.setStatus("DELETED");
            return 0;
        });

        new ThreadSummaryListener(threadRepository, aiService, cacheService).processThreadSummary(11L);

        verify(threadRepository).completeSummaryIfNormal(eq(11L), eq("generated summary"), anyString(), any(), isNull());
        verify(cacheService).evict(11L);
        verify(cacheService, never()).put(11L, "generated summary", "COMPLETED");
    }
}
