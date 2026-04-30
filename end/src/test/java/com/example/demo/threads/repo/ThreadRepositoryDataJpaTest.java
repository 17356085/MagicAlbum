package com.example.demo.threads.repo;

import com.example.demo.sections.entity.Section;
import com.example.demo.sections.repo.SectionRepository;
import com.example.demo.posts.entity.Post;
import com.example.demo.threads.dto.ThreadQueryView;
import com.example.demo.threads.dto.ThreadRankingView;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.entity.ThreadLike;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserProfile;
import com.example.demo.user.repo.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisabledInAotMode
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ba_repo;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.driverClassName=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class ThreadRepositoryDataJpaTest {

    @Autowired ThreadRepository threadRepository;
    @Autowired SectionRepository sectionRepository;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;

    @BeforeEach
    void cleanup() {
        entityManager.createQuery("DELETE FROM ThreadLike").executeUpdate();
        entityManager.createQuery("DELETE FROM Post").executeUpdate();
        threadRepository.deleteAll();
        entityManager.createQuery("DELETE FROM UserProfile").executeUpdate();
        userRepository.deleteAll();
        sectionRepository.deleteAll();
    }

    @Test
    void searchNewestView_should_filter_by_status_and_return_projection_fields() {
        Section section = new Section();
        section.setName("Tech Projection");
        section.setSlug("tech-projection");
        section = sectionRepository.save(section);

        User user = new User();
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setPhone("13000000000");
        user.setPasswordHash("hash");
        user = userRepository.save(user);

        UserProfile profile = new UserProfile();
        profile.setUserId(user.getId());
        profile.setNickname("Alice");
        profile.setAvatarUrl("/uploads/avatar.png");
        entityManager.persist(profile);
        entityManager.flush();

        Thread normalOld = thread(section.getId(), user.getId(), "Old", "hello", "NORMAL", "2025-01-01T00:00:00Z");
        threadRepository.save(normalOld);

        Thread deletedNew = thread(section.getId(), user.getId(), "New but deleted", "hello", "DELETED", "2025-01-03T00:00:00Z");
        threadRepository.save(deletedNew);

        Thread normalNew = thread(section.getId(), user.getId(), "New", "world", "NORMAL", "2025-01-02T00:00:00Z");
        threadRepository.save(normalNew);

        Page<ThreadQueryView> page = threadRepository.searchNewestView(null, null, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent().get(0).getTitle()).isEqualTo("New");
        assertThat(page.getContent().get(0).getSectionName()).isEqualTo("Tech Projection");
        assertThat(page.getContent().get(0).getAuthorUsername()).isEqualTo("alice");
        assertThat(page.getContent().get(0).getAuthorNickname()).isEqualTo("Alice");
        assertThat(page.getContent().get(0).getAuthorAvatar()).isEqualTo("/uploads/avatar.png");
        assertThat(page.getContent().get(1).getTitle()).isEqualTo("Old");
    }

    @Test
    void searchNewestView_should_support_q_filter_case_insensitive() {
        Section section = new Section();
        section.setName("Tech Query");
        section.setSlug("tech-query");
        section = sectionRepository.save(section);

        User user = new User();
        user.setUsername("bob");
        user.setEmail("bob@example.com");
        user.setPhone("13000000001");
        user.setPasswordHash("hash");
        user = userRepository.save(user);

        threadRepository.save(thread(section.getId(), user.getId(), "Hello Java", "abc", "NORMAL", "2025-01-01T00:00:00Z"));
        threadRepository.save(thread(section.getId(), user.getId(), "Other", "Vue", "NORMAL", "2025-01-02T00:00:00Z"));

        Page<ThreadQueryView> page = threadRepository.searchNewestView("java", null, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getTitle()).isEqualTo("Hello Java");
    }

    @Test
    void rankingView_should_order_by_reply_and_like_weight() {
        Section section = new Section();
        section.setName("Tech Ranking");
        section.setSlug("tech-ranking");
        section = sectionRepository.save(section);

        User user = new User();
        user.setUsername("carol");
        user.setEmail("carol@example.com");
        user.setPhone("13000000002");
        user.setPasswordHash("hash");
        user = userRepository.save(user);

        User liker = new User();
        liker.setUsername("dan");
        liker.setEmail("dan@example.com");
        liker.setPhone("13000000003");
        liker.setPasswordHash("hash");
        liker = userRepository.save(liker);

        Thread replyHeavy = thread(section.getId(), user.getId(), "Reply heavy", "a", "NORMAL", "2025-01-01T00:00:00Z");
        replyHeavy = threadRepository.save(replyHeavy);
        Thread likeHeavy = thread(section.getId(), user.getId(), "Like heavy", "b", "NORMAL", "2025-01-02T00:00:00Z");
        likeHeavy = threadRepository.save(likeHeavy);

        entityManager.persist(post(replyHeavy.getId(), user.getId(), "reply 1"));
        entityManager.persist(post(replyHeavy.getId(), user.getId(), "reply 2"));
        entityManager.persist(like(likeHeavy.getId(), liker.getId()));
        entityManager.flush();

        Page<ThreadRankingView> page = threadRepository.rankingView(section.getId(), PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent().get(0).getTitle()).isEqualTo("Reply heavy");
        assertThat(page.getContent().get(0).getReplyCount()).isEqualTo(2);
        assertThat(page.getContent().get(0).getLikeCount()).isEqualTo(0);
        assertThat(page.getContent().get(0).getHotScore()).isEqualTo(4);
        assertThat(page.getContent().get(1).getTitle()).isEqualTo("Like heavy");
        assertThat(page.getContent().get(1).getHotScore()).isEqualTo(3);
    }

    private Thread thread(Long sectionId, Long authorId, String title, String content, String status, String createdAt) {
        Thread thread = new Thread();
        thread.setSectionId(sectionId);
        thread.setAuthorId(authorId);
        thread.setTitle(title);
        thread.setContentMd(content);
        thread.setStatus(status);
        thread.setCreatedAt(Instant.parse(createdAt));
        thread.setUpdatedAt(Instant.parse(createdAt));
        return thread;
    }

    private Post post(Long threadId, Long authorId, String content) {
        Post post = new Post();
        post.setThreadId(threadId);
        post.setAuthorId(authorId);
        post.setContentMd(content);
        post.setStatus("NORMAL");
        return post;
    }

    private ThreadLike like(Long threadId, Long userId) {
        ThreadLike like = new ThreadLike();
        like.setThreadId(threadId);
        like.setUserId(userId);
        return like;
    }
}
