package com.example.demo.notifications.outbox;

import com.example.demo.notifications.client.NotificationClient;
import com.example.demo.notifications.service.NotificationService;
import com.example.demo.posts.dto.CreatePostRequest;
import com.example.demo.posts.repo.PostRepository;
import com.example.demo.posts.service.PostService;
import com.example.demo.sections.entity.Section;
import com.example.demo.sections.repo.SectionRepository;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import com.example.demo.user.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "app.notifications.client.mode=remote",
        "app.notifications.client.enabled=true",
        "app.notifications.client.internal-service-token=integration-test-token-0123456789-abcdef",
        "app.notifications.outbox.scheduling-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:outbox-business;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false",
        "spring.jpa.show-sql=false"
})
class NotificationBusinessTransactionIntegrationTest {
    @Autowired PostService posts;
    @Autowired PostRepository postRepository;
    @Autowired UserRepository users;
    @Autowired SectionRepository sections;
    @Autowired ThreadRepository threads;
    @Autowired NotificationOutboxRepository outbox;
    @Autowired NotificationClient notificationClient;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean NotificationService notificationService;
    @MockitoBean UserSettingsService settings;
    @MockitoBean StringRedisTemplate redis;
    private Long actorId;
    private Long recipientId;
    private Long threadId;

    @BeforeEach
    void seedBusinessData() {
        outbox.deleteAll();
        postRepository.deleteAll();
        threads.deleteAll();
        sections.deleteAll();
        users.deleteAll();
        when(settings.shouldDeliver(anyLong(), anyString())).thenReturn(true);
        actorId = user("actor");
        recipientId = user("recipient");
        var section = new Section();
        section.setName("Business transaction");
        section.setSlug("business-transaction");
        section = sections.save(section);
        var thread = new com.example.demo.threads.entity.Thread();
        thread.setAuthorId(recipientId);
        thread.setSectionId(section.getId());
        thread.setTitle("A test thread");
        thread.setContentMd("A test thread body");
        threadId = threads.save(thread).getId();
    }

    @Test
    void actual_post_service_commits_post_and_queue_together_without_http() {
        var response = posts.create(actorId, threadId, request());
        var saved = postRepository.findById(response.getId()).orElseThrow();
        var queued = outbox.findAll();
        assertThat(saved.getContentMd()).isEqualTo("A reply");
        assertThat(queued).hasSize(1);
        assertThat(queued.getFirst().getRecipientUserId()).isEqualTo(recipientId);
        assertThat(queued.getFirst().getTargetId()).isEqualTo(saved.getId());
        assertThat(queued.getFirst().getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        verifyNoInteractions(notificationService);
    }

    @Test
    void preference_database_failure_rolls_back_the_already_saved_post() {
        when(settings.shouldDeliver(recipientId, "reply")).thenThrow(new IllegalStateException("preference database unavailable"));
        assertThatThrownBy(() -> posts.create(actorId, threadId, request())).isInstanceOf(IllegalStateException.class);
        assertThat(postRepository.count()).isZero();
        assertThat(outbox.count()).isZero();
        verifyNoInteractions(notificationService);
    }

    @Test
    void actual_queue_constraint_failure_rolls_back_the_already_saved_post() {
        jdbc.execute("ALTER TABLE notification_outbox ADD CONSTRAINT test_reject_notification CHECK (recipient_user_id < 0)");
        try {
            assertThatThrownBy(() -> posts.create(actorId, threadId, request())).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(postRepository.count()).isZero();
            assertThat(outbox.count()).isZero();
            verifyNoInteractions(notificationService);
        } finally {
            jdbc.execute("ALTER TABLE notification_outbox DROP CONSTRAINT test_reject_notification");
        }
    }

    @Test
    void direct_remote_notification_requires_a_business_transaction() {
        assertThatThrownBy(() -> notificationClient.sendReply(recipientId, "Reply", "Body"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(outbox.count()).isZero();
    }

    private Long user(String name) {
        var user = new User();
        user.setUsername(name);
        user.setEmail(name + "@business.test");
        user.setPhone("actor".equals(name) ? "13000000001" : "13000000002");
        user.setPasswordHash("test-hash");
        return users.save(user).getId();
    }

    private CreatePostRequest request() {
        var request = new CreatePostRequest();
        request.setContentMd("A reply");
        return request;
    }
}
