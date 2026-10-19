package com.example.demo.threads.service;

import com.example.demo.ai.service.AiSummaryCacheService;
import com.example.demo.common.config.RabbitMQConfig;
import com.example.demo.sections.repo.SectionRepository;
import com.example.demo.threads.dto.CreateThreadRequest;
import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.dto.ThreadQueryView;
import com.example.demo.threads.dto.UpdateThreadRequest;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.event.service.ContentEventRecorder;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.threads.service.mp.ThreadReadServiceMp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThreadServiceTest {

    @Mock ThreadRepository threadRepository;
    @Mock SectionRepository sectionRepository;
    @Mock MarkdownRenderService markdownRenderService;
    @Mock RabbitTemplate rabbitTemplate;
    @Mock ContentEventRecorder contentEventRecorder;

    @Test
    void hiddenThreadIsNotReadableAndModeratorHidePersistsStatus() {
        ObjectProvider<ThreadReadServiceMp> mpProvider = mock(ObjectProvider.class);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        ThreadService service = new ThreadService(
                threadRepository, sectionRepository, markdownRenderService,
                mpProvider, false, rabbitProvider, false, contentEventRecorder);
        Thread thread = new Thread();
        thread.setId(8L);
        thread.setStatus("NORMAL");
        when(threadRepository.findByIdForInteraction(8L)).thenReturn(Optional.of(thread));
        when(threadRepository.findById(8L)).thenReturn(Optional.of(thread));

        service.hideForModeration(8L);

        assertThat(thread.getStatus()).isEqualTo("HIDDEN");
        verify(threadRepository).save(thread);
        assertThatThrownBy(() -> service.getById(8L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void hideAndDelete_should_evict_ai_summary_cache_after_status_change() {
        ObjectProvider<ThreadReadServiceMp> mpProvider = mock(ObjectProvider.class);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        AiSummaryCacheService aiSummaryCacheService = mock(AiSummaryCacheService.class);
        ThreadService service = new ThreadService(
                threadRepository, null, sectionRepository, null, markdownRenderService,
                mpProvider, false, rabbitProvider, false, null, null, null, null, aiSummaryCacheService,
                contentEventRecorder);
        Thread thread = new Thread();
        thread.setId(8L);
        thread.setAuthorId(1L);
        thread.setStatus("NORMAL");
        when(threadRepository.findByIdForInteraction(8L)).thenReturn(Optional.of(thread));

        service.hideForModeration(8L);
        service.delete(1L, 8L);

        verify(aiSummaryCacheService, times(2)).evict(8L);
        verify(markdownRenderService, times(2)).evict(8L);
    }

    @Test
    void create_should_publish_summary_only_after_transaction_commit() {
        ObjectProvider<ThreadReadServiceMp> mpProvider = mock(ObjectProvider.class);
        when(mpProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(rabbitTemplate);

        ThreadService service = new ThreadService(
                threadRepository,
                sectionRepository,
                markdownRenderService,
                mpProvider,
                false,
                rabbitProvider,
                true,
                contentEventRecorder
        );

        when(sectionRepository.existsById(7L)).thenReturn(true);
        when(threadRepository.save(any(Thread.class))).thenAnswer(inv -> {
            Thread t = inv.getArgument(0);
            t.setId(123L);
            return t;
        });
        ThreadQueryView createdView = view(
                123L,
                7L,
                "Tech",
                1L,
                "alice",
                "Alice",
                "/uploads/avatar.png",
                "Hello",
                "World"
        );
        when(threadRepository.findViewsByIdIn(List.of(123L))).thenReturn(List.of(createdView));

        CreateThreadRequest req = new CreateThreadRequest();
        req.setSectionId(7L);
        req.setTitle(" Hello ");
        req.setContent("World");

        TransactionSynchronizationManager.initSynchronization();
        ThreadDto dto;
        try {
            dto = service.create(1L, req);
            verify(rabbitTemplate, never()).convertAndSend(RabbitMQConfig.QUEUE_THREAD_SUMMARY, 123L);

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        ArgumentCaptor<Thread> savedCaptor = ArgumentCaptor.forClass(Thread.class);
        verify(threadRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getTitle()).isEqualTo("Hello");
        assertThat(savedCaptor.getValue().getSectionId()).isEqualTo(7L);
        assertThat(savedCaptor.getValue().getAuthorId()).isEqualTo(1L);

        verify(rabbitTemplate).convertAndSend(RabbitMQConfig.QUEUE_THREAD_SUMMARY, 123L);

        assertThat(dto.getId()).isEqualTo(123L);
        assertThat(dto.getSectionName()).isEqualTo("Tech");
        assertThat(dto.getAuthorUsername()).isEqualTo("alice");
        assertThat(dto.getAuthorNickname()).isEqualTo("Alice");
        assertThat(dto.getAuthorAvatar()).isEqualTo("/uploads/avatar.png");
        assertThat(dto.getTitle()).isEqualTo("Hello");
        assertThat(dto.getContent()).isEqualTo("World");
    }

    @Test
    void create_should_not_fail_when_rabbitmq_send_throws() {
        ObjectProvider<ThreadReadServiceMp> mpProvider = mock(ObjectProvider.class);
        when(mpProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(rabbitTemplate);

        ThreadService service = new ThreadService(
                threadRepository,
                sectionRepository,
                markdownRenderService,
                mpProvider,
                false,
                rabbitProvider,
                true,
                contentEventRecorder
        );

        when(sectionRepository.existsById(7L)).thenReturn(true);
        when(threadRepository.save(any(Thread.class))).thenAnswer(inv -> {
            Thread t = inv.getArgument(0);
            t.setId(123L);
            return t;
        });
        ThreadQueryView createdView = view(
                123L,
                7L,
                null,
                1L,
                null,
                null,
                null,
                "Hello",
                "World"
        );
        when(threadRepository.findViewsByIdIn(List.of(123L))).thenReturn(List.of(createdView));
        doThrow(new RuntimeException("rabbit down"))
                .when(rabbitTemplate).convertAndSend(RabbitMQConfig.QUEUE_THREAD_SUMMARY, 123L);

        CreateThreadRequest req = new CreateThreadRequest();
        req.setSectionId(7L);
        req.setTitle("Hello");
        req.setContent("World");

        ThreadDto dto = service.create(1L, req);
        assertThat(dto.getId()).isEqualTo(123L);
        assertThat(dto.getTitle()).isEqualTo("Hello");
    }

    @Test
    void create_should_accept_content_at_10000_characters() {
        ObjectProvider<ThreadReadServiceMp> mpProvider = mock(ObjectProvider.class);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        ThreadService service = new ThreadService(
                threadRepository, sectionRepository, markdownRenderService,
                mpProvider, false, rabbitProvider, false, contentEventRecorder);
        when(sectionRepository.existsById(7L)).thenReturn(true);
        when(threadRepository.save(any(Thread.class))).thenAnswer(invocation -> {
            Thread saved = invocation.getArgument(0);
            saved.setId(123L);
            return saved;
        });
        ThreadQueryView createdView = view(
                123L, 7L, null, 1L, null, null, null, "Hello", "x".repeat(10_000));
        when(threadRepository.findViewsByIdIn(List.of(123L))).thenReturn(List.of(createdView));

        CreateThreadRequest req = new CreateThreadRequest();
        req.setSectionId(7L);
        req.setTitle("Hello");
        req.setContent("x".repeat(10_000));

        service.create(1L, req);

        ArgumentCaptor<Thread> savedCaptor = ArgumentCaptor.forClass(Thread.class);
        verify(threadRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getContentMd()).hasSize(10_000);
    }

    @Test
    void create_should_reject_content_over_10000_characters() {
        ObjectProvider<ThreadReadServiceMp> mpProvider = mock(ObjectProvider.class);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        ThreadService service = new ThreadService(
                threadRepository, sectionRepository, markdownRenderService,
                mpProvider, false, rabbitProvider, false, contentEventRecorder);
        when(sectionRepository.existsById(7L)).thenReturn(true);

        CreateThreadRequest req = new CreateThreadRequest();
        req.setSectionId(7L);
        req.setTitle("Hello");
        req.setContent("x".repeat(10_001));

        assertThatThrownBy(() -> service.create(1L, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("内容过长");
        verify(threadRepository, never()).save(any(Thread.class));
    }

    @Test
    void update_should_accept_content_at_10000_characters() {
        ObjectProvider<ThreadReadServiceMp> mpProvider = mock(ObjectProvider.class);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        ThreadService service = new ThreadService(
                threadRepository, sectionRepository, markdownRenderService,
                mpProvider, false, rabbitProvider, false, contentEventRecorder);
        Thread thread = new Thread();
        thread.setId(8L);
        thread.setAuthorId(1L);
        thread.setSectionId(7L);
        thread.setStatus("NORMAL");
        thread.setTitle("Hello");
        thread.setContentMd("old");
        when(threadRepository.findByIdForInteraction(8L)).thenReturn(Optional.of(thread));
        when(threadRepository.save(any(Thread.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ThreadQueryView updatedView = view(
                8L, 7L, null, 1L, null, null, null, "Hello", "x".repeat(10_000));
        when(threadRepository.findViewsByIdIn(List.of(8L))).thenReturn(List.of(updatedView));

        UpdateThreadRequest req = new UpdateThreadRequest();
        req.setContent("x".repeat(10_000));

        service.update(1L, 8L, req);

        assertThat(thread.getContentMd()).hasSize(10_000);
        verify(threadRepository).save(thread);
    }

    @Test
    void update_should_reject_content_over_10000_characters() {
        ObjectProvider<ThreadReadServiceMp> mpProvider = mock(ObjectProvider.class);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        ThreadService service = new ThreadService(
                threadRepository, sectionRepository, markdownRenderService,
                mpProvider, false, rabbitProvider, false, contentEventRecorder);
        Thread thread = new Thread();
        thread.setId(8L);
        thread.setAuthorId(1L);
        thread.setStatus("NORMAL");
        when(threadRepository.findByIdForInteraction(8L)).thenReturn(Optional.of(thread));

        UpdateThreadRequest req = new UpdateThreadRequest();
        req.setContent("x".repeat(10_001));

        assertThatThrownBy(() -> service.update(1L, 8L, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("内容过长");
        verify(threadRepository, never()).save(any(Thread.class));
    }

    @Test
    void create_should_reject_missing_section() {
        ObjectProvider<ThreadReadServiceMp> mpProvider = mock(ObjectProvider.class);
        when(mpProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(rabbitTemplate);

        ThreadService service = new ThreadService(
                threadRepository,
                sectionRepository,
                markdownRenderService,
                mpProvider,
                false,
                rabbitProvider,
                true,
                contentEventRecorder
        );

        when(sectionRepository.existsById(7L)).thenReturn(false);

        CreateThreadRequest req = new CreateThreadRequest();
        req.setSectionId(7L);
        req.setTitle("Hello");
        req.setContent("World");

        assertThatThrownBy(() -> service.create(1L, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("分区不存在");
    }

    private ThreadQueryView view(
            Long id,
            Long sectionId,
            String sectionName,
            Long authorId,
            String authorUsername,
            String authorNickname,
            String authorAvatar,
            String title,
            String content
    ) {
        ThreadQueryView view = mock(ThreadQueryView.class);
        when(view.getId()).thenReturn(id);
        when(view.getSectionId()).thenReturn(sectionId);
        when(view.getSectionName()).thenReturn(sectionName);
        when(view.getAuthorId()).thenReturn(authorId);
        when(view.getAuthorUsername()).thenReturn(authorUsername);
        when(view.getAuthorNickname()).thenReturn(authorNickname);
        when(view.getAuthorAvatar()).thenReturn(authorAvatar);
        when(view.getTitle()).thenReturn(title);
        when(view.getContent()).thenReturn(content);
        when(view.getStatus()).thenReturn("NORMAL");
        return view;
    }
}
