package com.example.demo.posts.service;

import com.example.demo.posts.dto.PostDto;
import com.example.demo.posts.entity.Post;
import com.example.demo.posts.entity.PostLike;
import com.example.demo.threads.entity.Thread;
import com.example.demo.user.entity.User;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:post_like_list_queries;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false"
})
class PostServiceListIntegrationTest {

    @Autowired
    private PostService postService;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private com.example.demo.notifications.client.NotificationClient notificationClient;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    private Long threadId;
    private Long authorId;
    private Long viewerOneId;
    private Long viewerTwoId;
    private List<Long> postIds;
    private Statistics statistics;

    @BeforeEach
    void createFixture() {
        User author = persistUser("post-list-author", 0);
        User viewerOne = persistUser("post-list-viewer-one", 1);
        User viewerTwo = persistUser("post-list-viewer-two", 2);
        authorId = author.getId();
        viewerOneId = viewerOne.getId();
        viewerTwoId = viewerTwo.getId();

        Thread thread = new Thread();
        thread.setSectionId(1L);
        thread.setAuthorId(authorId);
        thread.setTitle("Post list thread");
        thread.setContentMd("thread content");
        thread.setStatus("NORMAL");
        thread.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        thread.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        thread = entityManager.merge(thread);
        threadId = thread.getId();

        postIds = new ArrayList<>(51);
        for (int i = 0; i < 51; i++) {
            Instant createdAt = Instant.parse("2026-02-01T00:00:00Z").plusSeconds(i);
            Post post = new Post();
            post.setThreadId(threadId);
            post.setAuthorId(authorId);
            post.setContentMd("comment-" + i);
            post.setStatus("NORMAL");
            post.setCreatedAt(createdAt);
            post.setUpdatedAt(createdAt);
            entityManager.persist(post);
            entityManager.flush();
            postIds.add(post.getId());

            int likeCount = i % 4;
            for (int likeIndex = 0; likeIndex < likeCount; likeIndex++) {
                PostLike like = new PostLike();
                like.setPostId(post.getId());
                like.setUserId((long) (1000 + likeIndex));
                entityManager.persist(like);
            }
            if (i % 5 == 0) {
                PostLike viewerLike = new PostLike();
                viewerLike.setPostId(post.getId());
                viewerLike.setUserId(viewerOneId);
                entityManager.persist(viewerLike);
            }
            if (i % 7 == 0) {
                PostLike viewerLike = new PostLike();
                viewerLike.setPostId(post.getId());
                viewerLike.setUserId(viewerTwoId);
                entityManager.persist(viewerLike);
            }
        }
        entityManager.flush();
        entityManager.clear();
        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

    @Test
    void listQueriesStayConstantAsPageSizeGrows() {
        for (int size : new int[]{1, 50}) {
            long authenticated = statementCount(() -> postService.listByThread(
                    threadId, viewerOneId, "time", 1, size));
            long anonymous = statementCount(() -> postService.listByThread(
                    threadId, null, "time", 1, size));
            long author = statementCount(() -> postService.listByAuthor(
                    authorId, 1, size));
            long authorFiltered = statementCount(() -> postService.listByAuthor(
                    authorId, null, null, "createdAt", 1, size));

            System.out.println("SQL_STATEMENTS acceptance authenticated page=1 size=" + size + " actual=" + authenticated);
            System.out.println("SQL_STATEMENTS acceptance anonymous page=1 size=" + size + " actual=" + anonymous);
            System.out.println("SQL_STATEMENTS acceptance author page=1 size=" + size + " actual=" + author);
            System.out.println("SQL_STATEMENTS acceptance author-filtered page=1 size=" + size + " actual=" + authorFiltered);
            assertThat(authenticated).isEqualTo(5L);
            assertThat(anonymous).isEqualTo(4L);
            assertThat(author).isEqualTo(3L);
            assertThat(authorFiltered).isEqualTo(3L);
        }
    }

    @Test
    void listResultsPreserveFieldsOrderingViewerStateAndLikeSort() {
        var viewerOnePage = postService.listByThread(threadId, viewerOneId, "time", 1, 50);
        var viewerTwoPage = postService.listByThread(threadId, viewerTwoId, "time", 1, 50);
        var anonymousPage = postService.listByThread(threadId, null, "time", 1, 50);
        var authorPage = postService.listByAuthor(authorId, null, null, "createdAt", 1, 50);

        assertThat(anonymousPage.getContent()).noneMatch(PostDto::isLiked);
        assertThat(viewerOnePage.getTotalElements()).isEqualTo(51);
        assertThat(viewerOnePage.getContent()).hasSize(50);
        assertThat(viewerOnePage.getContent()).extracting(PostDto::getContent)
                .containsExactlyElementsOf(contents(0, 50));
        assertThat(viewerOnePage.getContent().get(0).getThreadId()).isEqualTo(threadId);
        assertThat(viewerOnePage.getContent().get(0).getThreadTitle()).isEqualTo("Post list thread");
        assertThat(viewerOnePage.getContent().get(0).getAuthorId()).isEqualTo(authorId);
        assertThat(viewerOnePage.getContent().get(0).getAuthorUsername()).isEqualTo("post-list-author");
        assertThat(viewerOnePage.getContent().get(0).getCreatedAt())
                .isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));

        Map<Long, com.example.demo.posts.dto.PostDto> viewerOneById = byId(viewerOnePage.getContent());
        Map<Long, com.example.demo.posts.dto.PostDto> viewerTwoById = byId(viewerTwoPage.getContent());
        assertThat(viewerOneById.get(postIds.get(0)).isLiked()).isTrue();
        assertThat(viewerTwoById.get(postIds.get(0)).isLiked()).isTrue();
        assertThat(viewerOneById.get(postIds.get(5)).isLiked()).isTrue();
        assertThat(viewerTwoById.get(postIds.get(5)).isLiked()).isFalse();
        assertThat(viewerOneById.get(postIds.get(7)).isLiked()).isFalse();
        assertThat(viewerTwoById.get(postIds.get(7)).isLiked()).isTrue();
        assertThat(anonymousPage.getContent().get(4).getLikeCount()).isZero();
        assertThat(viewerOneById.get(postIds.get(3)).getLikeCount()).isEqualTo(expectedLikeCount(3));

        assertThat(authorPage.getContent()).extracting(PostDto::getContent)
                .containsExactlyElementsOf(contents(50, 0));
        assertThat(authorPage.getContent().get(0).getUpdatedAt())
                .isEqualTo(Instant.parse("2026-02-01T00:00:50Z"));

        var likeSorted = postService.listByThread(threadId, null, "likeCount", 1, 50);
        List<Integer> expectedSort = java.util.stream.IntStream.range(0, 51)
                .boxed()
                .sorted(Comparator.comparingInt(this::expectedLikeCount).reversed())
                .limit(50)
                .toList();
        assertThat(likeSorted.getContent()).extracting(PostDto::getContent)
                .containsExactlyElementsOf(expectedSort.stream().map(i -> "comment-" + i).toList());
        assertThat(likeSorted.getContent()).allSatisfy(dto ->
                assertThat(dto.getLikeCount()).isEqualTo(expectedLikeCount(
                        Integer.parseInt(dto.getContent().substring("comment-".length())))));
    }

    @Test
    void emptyPageSkipsLikeQueries() {
        long statements = statementCount(() -> {
            var page = postService.listByThread(threadId, viewerOneId, "time", 3, 50);
            assertThat(page.getContent()).isEmpty();
            assertThat(page.getTotalElements()).isEqualTo(51);
        });

        System.out.println("SQL_STATEMENTS acceptance empty authenticated page=3 size=50 actual=" + statements);
        assertThat(statements).isEqualTo(3L);
    }

    private long statementCount(Runnable operation) {
        entityManager.clear();
        statistics.clear();
        operation.run();
        return statistics.getPrepareStatementCount();
    }

    private static List<String> contents(int startInclusive, int endExclusive) {
        List<String> contents = new ArrayList<>();
        if (startInclusive <= endExclusive) {
            for (int i = startInclusive; i < endExclusive; i++) {
                contents.add("comment-" + i);
            }
        } else {
            for (int i = startInclusive; i > endExclusive; i--) {
                contents.add("comment-" + i);
            }
        }
        return contents;
    }

    private static Map<Long, com.example.demo.posts.dto.PostDto> byId(
            List<com.example.demo.posts.dto.PostDto> items) {
        return items.stream().collect(Collectors.toMap(
                com.example.demo.posts.dto.PostDto::getId, Function.identity()));
    }

    private int expectedLikeCount(int index) {
        return (index % 4) + (index % 5 == 0 ? 1 : 0) + (index % 7 == 0 ? 1 : 0);
    }

    private User persistUser(String username, int suffix) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPhone("139" + String.format("%08d", suffix));
        user.setPasswordHash("test-only-hash");
        user.setCreatedAt(OffsetDateTime.of(2026, 1, 1, 0, 0, suffix, 0, ZoneOffset.UTC));
        entityManager.persist(user);
        return user;
    }
}
