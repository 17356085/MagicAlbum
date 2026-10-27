package com.example.demo.threads.service;

import com.example.demo.posts.entity.Post;
import com.example.demo.sections.entity.Section;
import com.example.demo.tags.entity.Tag;
import com.example.demo.tags.entity.ThreadTag;
import com.example.demo.tags.service.TagService;
import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.dto.ThreadQueryView;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.entity.ThreadLike;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserProfile;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.threads.service.SqlStatementCountingConfiguration.PreparedStatementCounter;
import com.example.demo.threads.repo.ThreadLikeRepository;
import com.example.demo.threads.repo.ThreadRepository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
@Import(SqlStatementCountingConfiguration.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:thread_list_queries;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false"
})
class ThreadServiceListIntegrationTest {
    private static final int THREAD_COUNT = 100;

    @Autowired
    private ThreadService threadService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ThreadRepository threadRepository;

    @Autowired
    private ThreadLikeRepository threadLikeRepository;

    @Autowired
    private TagService tagService;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @Autowired
    private PreparedStatementCounter preparedStatementCounter;

    private ValueOperations<String, String> valueOperations;
    private Long sectionId;
    private Long authorId;
    private final List<ThreadFixture> fixtures = new ArrayList<>();
    private Statistics statistics;

    @BeforeEach
    void createFixture() {
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        Section section = new Section();
        section.setName("Thread list section");
        section.setSlug("thread-list-section");
        entityManager.persist(section);

        User author = new User();
        author.setUsername("thread-list-author");
        author.setEmail("thread-list-author@example.com");
        author.setPhone("13900000001");
        author.setPasswordHash("test-only-hash");
        author.setCreatedAt(OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        entityManager.persist(author);

        UserProfile profile = new UserProfile();
        profile.setUserId(author.getId());
        profile.setNickname("列表作者");
        profile.setAvatarUrl("/uploads/thread-list-author.png");
        entityManager.persist(profile);
        entityManager.flush();
        sectionId = section.getId();
        authorId = author.getId();

        Tag alpha = tag("alpha");
        Tag omega = tag("omega");
        Tag zeta = tag("zeta");
        entityManager.flush();

        fixtures.clear();
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        for (int index = 0; index < THREAD_COUNT; index++) {
            Thread thread = new Thread();
            thread.setSectionId(sectionId);
            thread.setAuthorId(authorId);
            thread.setTitle("thread-" + index);
            thread.setContentMd("thread-content-" + index);
            thread.setStatus("NORMAL");
            thread.setCreatedAt(start.plusSeconds(index));
            thread.setUpdatedAt(start.plusSeconds(index));
            entityManager.persist(thread);
            entityManager.flush();

            int likeCount = index % 3;
            for (int likeIndex = 0; likeIndex < likeCount; likeIndex++) {
                ThreadLike like = new ThreadLike();
                like.setThreadId(thread.getId());
                like.setUserId(10_000L + index * 10L + likeIndex);
                entityManager.persist(like);
            }

            int replyCount = index % 4;
            for (int replyIndex = 0; replyIndex < replyCount; replyIndex++) {
                Post post = new Post();
                post.setThreadId(thread.getId());
                post.setAuthorId(authorId);
                post.setContentMd("reply-" + index + "-" + replyIndex);
                post.setStatus("NORMAL");
                post.setCreatedAt(start.plusSeconds(index * 10L + replyIndex));
                post.setUpdatedAt(start.plusSeconds(index * 10L + replyIndex));
                entityManager.persist(post);
            }

            if (index % 2 == 0) {
                entityManager.persist(threadTag(thread.getId(), zeta.getId()));
                entityManager.persist(threadTag(thread.getId(), alpha.getId()));
            } else {
                entityManager.persist(threadTag(thread.getId(), omega.getId()));
            }
            fixtures.add(new ThreadFixture(thread.getId(), index, likeCount, replyCount));
        }
        entityManager.flush();
        entityManager.clear();
        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

    @Test
    void jpaListUsesConstantBatchQueriesOnPageOneAndPageTen() {
        for (int size : List.of(1, 10)) {
            QueryCounts counts = measure(() -> {
                var result = threadService.list(null, null, sectionId, 1, size);
                assertThat(result.getContent()).hasSize(size);
                assertThat(result.getTotalElements()).isEqualTo(THREAD_COUNT);
                assertThat(result.getContent()).allSatisfy(dto -> {
                    assertThat(dto.getAuthorId()).isEqualTo(authorId);
                    assertThat(dto.getAuthorUsername()).isEqualTo("thread-list-author");
                    assertThat(dto.getAuthorNickname()).isEqualTo("列表作者");
                    assertThat(dto.getAuthorAvatar()).isEqualTo("/uploads/thread-list-author.png");
                    assertThat(dto.getSectionId()).isEqualTo(sectionId);
                });
            });

            System.out.println("THREAD_QUERY_COUNTS jpa-list page=1 size=" + size
                    + " hibernate=" + counts.hibernate()
                    + " jdbcTags=" + counts.jdbcTags()
                    + " total=" + counts.total());
            assertThat(counts.hibernate()).isEqualTo(3L);
            assertThat(counts.jdbcTags()).isEqualTo(1L);
            assertThat(counts.total()).isEqualTo(4L);
        }

        var page = threadService.list(null, null, sectionId, 1, 10).getContent();
        Map<String, ThreadDto> byTitle = page.stream().collect(java.util.stream.Collectors.toMap(
                ThreadDto::getTitle, dto -> dto));
        assertThat(byTitle.get("thread-99").getLikeCount()).isZero();
        assertThat(byTitle.get("thread-98").getLikeCount()).isEqualTo(2L);
        assertThat(byTitle.get("thread-98").getReplyCount()).isEqualTo(2L);
        assertThat(byTitle.get("thread-98").getTags()).containsExactly("alpha", "zeta");
        assertThat(byTitle.get("thread-99").getTags()).containsExactly("omega");
    }

    @Test
    void bothAuthorListPathsUseBatchEnrichment() {
        for (int size : List.of(1, 10)) {
            QueryCounts updated = measure(() -> {
                var result = threadService.listByAuthor(authorId, "updatedAt", 1, size);
                assertThat(result.getContent()).hasSize(size);
                assertThat(result.getContent().get(0).getTitle()).isEqualTo("thread-99");
            });
            QueryCounts created = measure(() -> {
                var result = threadService.listByAuthor(authorId, "thread-", sectionId, "createdAt", 1, size);
                assertThat(result.getContent()).hasSize(size);
                assertThat(result.getContent().get(0).getTitle()).isEqualTo("thread-99");
            });

            System.out.println("THREAD_QUERY_COUNTS author-updated size=" + size
                    + " hibernate=" + updated.hibernate()
                    + " jdbcTags=" + updated.jdbcTags() + " total=" + updated.total());
            System.out.println("THREAD_QUERY_COUNTS author-created-filtered size=" + size
                    + " hibernate=" + created.hibernate()
                    + " jdbcTags=" + created.jdbcTags() + " total=" + created.total());
            assertThat(updated).isEqualTo(new QueryCounts(3L, 1L, 4L));
            assertThat(created).isEqualTo(new QueryCounts(3L, 1L, 4L));
        }
    }

    @Test
    void rankingKeepsProjectionMetadataAndBatchesOnlyTags() {
        for (int size : List.of(1, 20)) {
            QueryCounts counts = measure(() -> {
                var result = threadService.ranking(sectionId, 1, size);
                assertThat(result.getContent()).hasSize(size);
                assertThat(result.getTotalElements()).isEqualTo(THREAD_COUNT);
                assertThat(result.getContent()).isSortedAccordingTo(
                        Comparator.comparing(ThreadDto::getHotScore).reversed());
                for (ThreadDto dto : result.getContent()) {
                    ThreadFixture fixture = fixture(dto.getId());
                    assertThat(dto.getLikeCount()).isEqualTo(fixture.likeCount());
                    assertThat(dto.getReplyCount()).isEqualTo(fixture.replyCount());
                    assertThat(dto.getHotScore())
                            .isEqualTo(fixture.replyCount() * 2L + fixture.likeCount() * 3L);
                    assertThat(dto.getTags()).isEqualTo(fixture.index() % 2 == 0
                            ? List.of("alpha", "zeta") : List.of("omega"));
                    assertThat(dto.getAuthorNickname()).isEqualTo("列表作者");
                }
            });

            System.out.println("THREAD_QUERY_COUNTS ranking size=" + size
                    + " hibernate=" + counts.hibernate()
                    + " jdbcTags=" + counts.jdbcTags() + " total=" + counts.total());
            assertThat(counts).isEqualTo(new QueryCounts(2L, 1L, 3L));
        }
    }

    @Test
    void emptyPageSkipsLikeAndTagEnrichmentQueries() {
        QueryCounts counts = measure(() -> {
            var result = threadService.list(null, null, sectionId, 11, 10);
            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isEqualTo(THREAD_COUNT);
        });

        System.out.println("THREAD_QUERY_COUNTS empty page=11 size=10 hibernate=" + counts.hibernate()
                + " jdbcTags=" + counts.jdbcTags() + " total=" + counts.total());
        assertThat(counts).isEqualTo(new QueryCounts(2L, 0L, 2L));
    }

    @Test
    void batchMapperRechecksCurrentStatusAfterStaleEntitySnapshot() {
        Long hiddenId = fixtures.get(0).id();
        Long visibleFirstId = fixtures.get(3).id();
        Long deletedId = fixtures.get(2).id();
        Long visibleSecondId = fixtures.get(1).id();
        List<Long> ids = List.of(hiddenId, visibleFirstId, deletedId, visibleSecondId);

        List<Thread> staleSnapshots = ids.stream()
                .map(id -> {
                    Thread snapshot = entityManager.find(Thread.class, id);
                    entityManager.detach(snapshot);
                    return snapshot;
                })
                .toList();
        assertThat(staleSnapshots).allSatisfy(thread -> assertThat(thread.getStatus()).isEqualTo("NORMAL"));

        entityManager.createQuery("UPDATE Thread t SET t.status = 'HIDDEN' WHERE t.id = :id")
                .setParameter("id", hiddenId)
                .executeUpdate();
        entityManager.createQuery("UPDATE Thread t SET t.status = 'DELETED' WHERE t.id = :id")
                .setParameter("id", deletedId)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        List<ThreadQueryView> currentViews = threadRepository.findViewsByIdIn(ids);
        Map<Long, String> currentStatuses = currentViews.stream().collect(
                java.util.stream.Collectors.toMap(ThreadQueryView::getId, ThreadQueryView::getStatus));
        assertThat(currentStatuses).containsEntry(hiddenId, "HIDDEN")
                .containsEntry(deletedId, "DELETED")
                .containsEntry(visibleFirstId, "NORMAL")
                .containsEntry(visibleSecondId, "NORMAL");

        ThreadDtoMapper mapper = new ThreadDtoMapper(threadRepository, threadLikeRepository, tagService);
        List<ThreadDto> result = mapper.toDtosFromEntities(staleSnapshots);

        assertThat(result).extracting(ThreadDto::getId).containsExactly(visibleFirstId, visibleSecondId);
    }

    private QueryCounts measure(Runnable operation) {
        entityManager.clear();
        statistics.clear();
        preparedStatementCounter.reset();
        operation.run();
        long hibernate = statistics.getPrepareStatementCount();
        long total = preparedStatementCounter.count();
        long jdbcTags = total - hibernate;
        return new QueryCounts(hibernate, jdbcTags, total);
    }

    private ThreadFixture fixture(Long id) {
        return fixtures.stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    private Tag tag(String name) {
        Tag tag = new Tag();
        tag.setName(name);
        tag.setType("thread");
        entityManager.persist(tag);
        return tag;
    }

    private ThreadTag threadTag(Long threadId, Long tagId) {
        ThreadTag threadTag = new ThreadTag();
        threadTag.setThreadId(threadId);
        threadTag.setTagId(tagId);
        return threadTag;
    }

    private record ThreadFixture(Long id, int index, long likeCount, long replyCount) {
    }

    private record QueryCounts(long hibernate, long jdbcTags, long total) {
    }
}
