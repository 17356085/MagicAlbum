package com.example.demo.chat;

import com.example.demo.chat.controller.ChatController;
import com.example.demo.chat.repo.ChatRepository;
import com.example.demo.chat.service.ChatMessageModeration;
import com.example.demo.chat.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ChatOwnerModeTest {
    @Test
    void coreWithRemoteOwnerDoesNotRegisterTheLegacyChatBusinessEntry() {
        new ApplicationContextRunner()
                .withUserConfiguration(ChatController.class, ChatService.class)
                .withPropertyValues("app.chat.owner=remote")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ChatController.class);
                    assertThat(context).doesNotHaveBean(ChatService.class);
                });
    }

    @Test
    void coreWithRemoteOwnerCannotInspectOrChangeTheLegacyChatRows() {
        var repository = mock(ChatRepository.class);
        var moderation = new ChatMessageModeration(repository, null, "remote");
        assertBlocked(() -> moderation.requireReportable(1, 2));
        assertBlocked(() -> moderation.reportEvidence(2));
        assertBlocked(() -> moderation.setMessageHidden(2, true));
        verifyNoInteractions(repository);
    }

    private static void assertBlocked(Runnable request) {
        assertThatThrownBy(request::run).isInstanceOfSatisfying(ResponseStatusException.class,
                denied -> assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
