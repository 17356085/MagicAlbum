package com.example.demo.threads.service;

import com.example.demo.common.config.RabbitMQConfig;
import com.example.demo.sections.repo.SectionRepository;
import com.example.demo.threads.dto.CreateThreadRequest;
import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.dto.ThreadQueryView;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.threads.service.mp.ThreadReadServiceMp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThreadServiceTest {

    @Mock ThreadRepository threadRepository;
    @Mock SectionRepository sectionRepository;
    @Mock MarkdownRenderService markdownRenderService;
    @Mock RabbitTemplate rabbitTemplate;

    @Test
    void create_should_validate_and_persist_and_send_async_task() {
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
                true
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

        ThreadDto dto = service.create(1L, req);

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
                true
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
                true
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
