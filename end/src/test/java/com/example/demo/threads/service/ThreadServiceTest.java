package com.example.demo.threads.service;

import com.example.demo.common.config.RabbitMQConfig;
import com.example.demo.sections.entity.Section;
import com.example.demo.sections.repo.SectionRepository;
import com.example.demo.threads.dto.CreateThreadRequest;
import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserProfileRepository;
import com.example.demo.user.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

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
    @Mock UserRepository userRepository;
    @Mock UserProfileRepository userProfileRepository;
    @Mock MarkdownRenderService markdownRenderService;
    @Mock RabbitTemplate rabbitTemplate;

    @Test
    void create_should_validate_and_persist_and_send_async_task() {
        ObjectProvider<com.example.demo.threads.service.mp.ThreadReadServiceMp> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        ThreadService service = new ThreadService(
                threadRepository,
                sectionRepository,
                userRepository,
                userProfileRepository,
                markdownRenderService,
                provider,
                false,
                rabbitTemplate
        );

        when(sectionRepository.existsById(7L)).thenReturn(true);
        Section section = new Section();
        section.setId(7L);
        section.setName("Tech");
        when(sectionRepository.findById(7L)).thenReturn(Optional.of(section));

        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        when(threadRepository.save(any(Thread.class))).thenAnswer(inv -> {
            Thread t = inv.getArgument(0);
            t.setId(123L);
            return t;
        });

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
        assertThat(dto.getTitle()).isEqualTo("Hello");
        assertThat(dto.getContent()).isEqualTo("World");
    }

    @Test
    void create_should_not_fail_when_rabbitmq_send_throws() {
        ObjectProvider<com.example.demo.threads.service.mp.ThreadReadServiceMp> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        ThreadService service = new ThreadService(
                threadRepository,
                sectionRepository,
                userRepository,
                userProfileRepository,
                markdownRenderService,
                provider,
                false,
                rabbitTemplate
        );

        when(sectionRepository.existsById(7L)).thenReturn(true);
        when(sectionRepository.findById(7L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.empty());
        when(threadRepository.save(any(Thread.class))).thenAnswer(inv -> {
            Thread t = inv.getArgument(0);
            t.setId(123L);
            return t;
        });
        doThrow(new RuntimeException("rabbit down"))
                .when(rabbitTemplate).convertAndSend(RabbitMQConfig.QUEUE_THREAD_SUMMARY, 123L);

        CreateThreadRequest req = new CreateThreadRequest();
        req.setSectionId(7L);
        req.setTitle("Hello");
        req.setContent("World");

        ThreadDto dto = service.create(1L, req);
        assertThat(dto.getId()).isEqualTo(123L);
    }

    @Test
    void create_should_reject_missing_section() {
        ObjectProvider<com.example.demo.threads.service.mp.ThreadReadServiceMp> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        ThreadService service = new ThreadService(
                threadRepository,
                sectionRepository,
                userRepository,
                userProfileRepository,
                markdownRenderService,
                provider,
                false,
                rabbitTemplate
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
}

