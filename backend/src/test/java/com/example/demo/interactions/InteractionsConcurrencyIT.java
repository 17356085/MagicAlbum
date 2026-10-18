package com.example.demo.interactions;

import com.example.demo.notifications.client.NotificationClient;
import com.example.demo.posts.dto.PostLikeResponse;
import com.example.demo.posts.entity.Post;
import com.example.demo.posts.repo.PostLikeRepository;
import com.example.demo.posts.repo.PostRepository;
import com.example.demo.posts.service.PostService;
import com.example.demo.threads.dto.ThreadLikeResponse;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadLikeRepository;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.threads.service.ThreadService;
import com.example.demo.user.dto.UserFollowResponse;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserFollowRepository;
import com.example.demo.user.repo.UserRepository;
import com.example.demo.user.service.UserFollowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = com.example.demo.Demo1Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisabledInAotMode
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InteractionsConcurrencyIT {

    private static final String DATABASE_NAME = "interactions_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private ThreadService threadService;

    @Autowired
    private PostService postService;

    @Autowired
    private UserFollowService userFollowService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ThreadRepository threadRepository;

    @Autowired
    private ThreadLikeRepository threadLikeRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private PostLikeRepository postLikeRepository;

    @Autowired
    private UserFollowRepository userFollowRepository;

    @MockitoBean
    private NotificationClient notificationClient;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + DATABASE_NAME
                + ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false;LOCK_TIMEOUT=10000");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("app.rabbit.enabled", () -> "false");
        registry.add("feature.mp.read.threads", () -> "false");
    }

    @BeforeEach
    void clearInteractionRows() {
        postLikeRepository.deleteAllInBatch();
        threadLikeRepository.deleteAllInBatch();
        userFollowRepository.deleteAllInBatch();
        postRepository.deleteAllInBatch();
        threadRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void concurrent_duplicate_interactions_are_idempotent_and_content_edit_invalidates_summary() throws Exception {
        User actor = userRepository.saveAndFlush(user("actor"));
        User target = userRepository.saveAndFlush(user("target"));

        Thread thread = new Thread();
        thread.setSectionId(1L);
        thread.setAuthorId(actor.getId());
        thread.setTitle("Concurrency thread");
        thread.setContentMd("old content");
        thread.setStatus("NORMAL");
        thread.setSummary("old summary");
        thread.setSummaryStatus("COMPLETED");
        thread.setSummaryOwner("old-owner");
        thread.setSummaryLeaseUntil(Instant.now().plusSeconds(300));
        thread.setSummaryAttempts(4);
        thread = threadRepository.saveAndFlush(thread);

        Post post = new Post();
        post.setThreadId(thread.getId());
        post.setAuthorId(actor.getId());
        post.setContentMd("comment");
        post.setStatus("NORMAL");
        post = postRepository.saveAndFlush(post);

        Long actorId = actor.getId();
        Long targetId = target.getId();
        Long threadId = thread.getId();
        Long postId = post.getId();

        List<ThreadLikeResponse> threadLikes = concurrently(
                () -> threadService.like(actorId, threadId));
        assertThat(threadLikes).hasSize(2).allMatch(ThreadLikeResponse::isLiked);
        assertThat(threadLikeRepository.countByThreadId(threadId)).isEqualTo(1L);

        List<ThreadLikeResponse> threadUnlikes = concurrently(
                () -> threadService.unlike(actorId, threadId));
        assertThat(threadUnlikes).hasSize(2).noneMatch(ThreadLikeResponse::isLiked);
        assertThat(threadLikeRepository.countByThreadId(threadId)).isZero();

        com.example.demo.threads.dto.UpdateThreadRequest update =
                new com.example.demo.threads.dto.UpdateThreadRequest();
        update.setContent("new content");
        threadService.update(actorId, threadId, update);
        Thread edited = threadRepository.findById(threadId).orElseThrow();
        assertThat(edited.getSummary()).isNull();
        assertThat(edited.getSummaryStatus()).isEqualTo("PENDING");
        assertThat(edited.getSummaryOwner()).isNull();
        assertThat(edited.getSummaryLeaseUntil()).isNull();
        assertThat(edited.getSummaryAttempts()).isZero();

        List<PostLikeResponse> postLikes = concurrently(
                () -> postService.like(actorId, postId));
        assertThat(postLikes).hasSize(2).allMatch(PostLikeResponse::isLiked);
        assertThat(postLikeRepository.countByPostId(postId)).isEqualTo(1L);

        List<PostLikeResponse> postUnlikes = concurrently(
                () -> postService.unlike(actorId, postId));
        assertThat(postUnlikes).hasSize(2).noneMatch(PostLikeResponse::isLiked);
        assertThat(postLikeRepository.countByPostId(postId)).isZero();

        List<UserFollowResponse> follows = concurrently(
                () -> userFollowService.follow(actorId, targetId));
        assertThat(follows).hasSize(2).allMatch(UserFollowResponse::isFollowing);
        assertThat(userFollowRepository.existsByFollowerIdAndFollowingId(actorId, targetId)).isTrue();
        assertThat(userFollowRepository.countByFollowerId(actorId)).isEqualTo(1L);

        List<UserFollowResponse> unfollows = concurrently(
                () -> userFollowService.unfollow(actorId, targetId));
        assertThat(unfollows).hasSize(2).noneMatch(UserFollowResponse::isFollowing);
        assertThat(userFollowRepository.existsByFollowerIdAndFollowingId(actorId, targetId)).isFalse();
        assertThat(userFollowRepository.countByFollowerId(actorId)).isZero();
    }

    private User user(String role) {
        String suffix = DATABASE_NAME.substring(DATABASE_NAME.length() - 12);
        User user = new User();
        user.setUsername("interaction_" + role + "_" + suffix);
        user.setEmail("interaction_" + role + "_" + suffix + "@example.test");
        user.setPhone("13" + Math.abs((role + suffix).hashCode()) % 1_000_000_000);
        user.setPasswordHash("test-password");
        user.setCreatedAt(OffsetDateTime.now());
        return user;
    }

    private <T> List<T> concurrently(Supplier<T> operation) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>(2);
        try {
            for (int i = 0; i < 2; i++) {
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    if (!ready.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrency workers did not become ready");
                    }
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrency start latch timed out");
                    }
                    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    return transaction.execute(status -> operation.get());
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>(2);
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
