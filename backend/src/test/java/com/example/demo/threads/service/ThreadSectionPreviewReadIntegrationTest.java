package com.example.demo.threads.service;

import com.example.demo.common.PageResponse;
import com.example.demo.search.service.ThreadSearchService;
import com.example.demo.sections.entity.Section;
import com.example.demo.tags.entity.Tag;
import com.example.demo.tags.entity.ThreadTag;
import com.example.demo.threads.dto.SectionThreadPreviewDto;
import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.entity.ThreadLike;
import com.example.demo.threads.repo.ThreadSectionPreviewRepository;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
@Import(SqlStatementCountingConfiguration.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:thread_section_preview_read;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "feature.mp.read.threads=false",
        "app.search.enabled=false",
        "app.rabbit.enabled=false"
})
class ThreadSectionPreviewReadIntegrationTest {
    private static final int SECTION_COUNT = 20;
    private static final int THREADS_PER_SECTION = 5;
    private static final int PREVIEW_SIZE = 4;
    private static final int CONTENT_BYTES = 20 * 1024;

    @Autowired
    private ThreadSectionPreviewService previewService;

    @Autowired
    private ThreadSearchService threadSearchService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PreparedStatementCounter preparedStatementCounter;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final List<Long> normalSectionIds = new ArrayList<>();
    private Long emptySectionId;
    private Statistics statistics;
    private ValueOperations<String, String> valueOperations;
    private Long authorId;
    private Long authorWithoutProfileId;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void createFixture() {
        normalSectionIds.clear();
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        User author = persistUser("section-preview-author", "13900003001");
        authorId = author.getId();
        UserProfile profile = new UserProfile();
        profile.setUserId(authorId);
        profile.setNickname("分区预览作者");
        profile.setAvatarUrl("/uploads/section-preview-author.png");
        entityManager.persist(profile);

        User authorWithoutProfile = persistUser("section-preview-no-profile", "13900003002");
        authorWithoutProfileId = authorWithoutProfile.getId();

        Tag tag = new Tag();
        tag.setName("section-preview");
        tag.setType("thread");
        entityManager.persist(tag);
        entityManager.flush();

        Instant start = Instant.parse("2026-02-01T00:00:00Z");
        for (int sectionIndex = 0; sectionIndex < SECTION_COUNT; sectionIndex++) {
            Section section = persistSection(sectionIndex);
            normalSectionIds.add(section.getId());
            for (int threadIndex = 0; threadIndex < THREADS_PER_SECTION; threadIndex++) {
                Instant createdAt = sectionIndex == 0
                        ? start.plusSeconds(999)
                        : start.plusSeconds(sectionIndex * 100L + threadIndex);
                Long threadId = persistThread(section.getId(), sectionIndex, threadIndex,
                        threadIndex == 4 ? authorWithoutProfileId : authorId, createdAt);

                ThreadLike like = new ThreadLike();
                like.setThreadId(threadId);
                like.setUserId(30_000L + sectionIndex * 100L + threadIndex);
                entityManager.persist(like);

                ThreadTag relation = new ThreadTag();
                relation.setThreadId(threadId);
                relation.setTagId(tag.getId());
                entityManager.persist(relation);
            }
            if (sectionIndex == 0) {
                persistThreadWithStatus(section.getId(), "hidden-thread", "HIDDEN", start.plusSeconds(2_000));
                persistThreadWithStatus(section.getId(), "deleted-thread", "DELETED", start.plusSeconds(1_999));
            }
        }

        Section emptySection = new Section();
        emptySection.setName("Empty section");
        emptySection.setSlug("section-preview-empty");
        entityManager.persist(emptySection);
        entityManager.flush();
        emptySectionId = emptySection.getId();

        entityManager.flush();
        entityManager.clear();
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        preparedStatementCounter.reset();
    }

    @Test
    void oneJdbcStatementReturnsBoundedRowsAndSlimJsonForOneTenAndTwentySections() throws Exception {
        for (int sectionCount : List.of(1, 10, 20)) {
            entityManager.clear();
            statistics.clear();
            preparedStatementCounter.reset();

            List<SectionThreadPreviewDto> response = previewService.read(
                    normalSectionIds.subList(0, sectionCount));
            byte[] raw = objectMapper.writeValueAsBytes(response);
            byte[] gzip = gzip(raw);

            assertThat(response).hasSize(sectionCount);
            response.forEach(this::assertSlimPreview);
            assertThat(preparedStatementCounter.count()).isEqualTo(1L);
            assertThat(statistics.getPrepareStatementCount()).isZero();

            OldResponseMeasure old = measureOldResponses(sectionCount);
            System.out.println("MEASUREMENT {"
                    + "\"test\":\"ThreadSectionPreviewReadIntegrationTest\","
                    + "\"path\":\"new-batch\","
                    + "\"sections\":" + sectionCount + ","
                    + "\"preparedStatements\":1,"
                    + "\"hibernatePreparedStatements\":0,"
                    + "\"jdbcPreparedStatements\":1,"
                    + "\"responseUtf8Bytes\":" + raw.length + ","
                    + "\"responseGzipBytes\":" + gzip.length + ","
                    + "\"oldResponseUtf8Bytes\":" + old.utf8Bytes() + ","
                    + "\"oldResponseGzipBytes\":" + old.gzipBytes()
                    + "}");
            assertThat(raw).isNotEmpty();
            assertThat(gzip).isNotEmpty();
        }
    }

    @Test
    void excludesHiddenDeletedAndUnknownSectionsAndKeepsSameTimestampIdOrder() {
        List<SectionThreadPreviewDto> response = previewService.read(List.of(
                normalSectionIds.get(0), emptySectionId, 9_007_199_254_740_993L));

        assertThat(response).extracting(SectionThreadPreviewDto::sectionId)
                .containsExactly(String.valueOf(normalSectionIds.get(0)),
                        String.valueOf(emptySectionId), "9007199254740993");
        SectionThreadPreviewDto populated = response.get(0);
        assertThat(populated.total()).isEqualTo(THREADS_PER_SECTION);
        assertThat(populated.items()).hasSize(PREVIEW_SIZE);
        assertThat(populated.items()).extracting(item -> item.title())
                .containsExactly(
                        "section-0-thread-4",
                        "section-0-thread-3",
                        "section-0-thread-2",
                        "section-0-thread-1");
        assertThat(response.get(1).total()).isZero();
        assertThat(response.get(1).items()).isEmpty();
        assertThat(response.get(2).total()).isZero();
        assertThat(response.get(2).items()).isEmpty();
    }

    @Test
    void deduplicatesInInputOrderSupportsFiftyIdsAndEmptyInputDoesNoSql() {
        List<SectionThreadPreviewDto> deduplicated = previewService.read(List.of(
                normalSectionIds.get(2), normalSectionIds.get(1), normalSectionIds.get(2), emptySectionId));
        assertThat(deduplicated).extracting(SectionThreadPreviewDto::sectionId)
                .containsExactly(String.valueOf(normalSectionIds.get(2)),
                        String.valueOf(normalSectionIds.get(1)), String.valueOf(emptySectionId));

        List<Long> fiftyIds = new ArrayList<>(normalSectionIds);
        while (fiftyIds.size() < 50) {
            fiftyIds.add(100_000L + fiftyIds.size());
        }
        preparedStatementCounter.reset();
        statistics.clear();
        assertThat(previewService.read(fiftyIds)).hasSize(50);
        assertThat(preparedStatementCounter.count()).isEqualTo(1L);

        preparedStatementCounter.reset();
        statistics.clear();
        assertThat(previewService.read(List.of())).isEmpty();
        assertThat(preparedStatementCounter.count()).isZero();
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    @Test
    void newJsonDoesNotExposeBodyCountsTagsOrUserContactFields() throws Exception {
        byte[] raw = objectMapper.writeValueAsBytes(previewService.read(List.of(normalSectionIds.get(0))));
        String json = new String(raw, StandardCharsets.UTF_8);

        assertThat(json).doesNotContain("content", "contentMd", "contentHtml", "likeCount", "replyCount",
                "tags", "email", "phone", "password", "passwordHash");
        assertThat(json).contains("section-preview-no-profile");
        assertThat(json).contains("\"authorNickname\":null");
    }

    private void assertSlimPreview(SectionThreadPreviewDto section) {
        assertThat(section.total()).isEqualTo(THREADS_PER_SECTION);
        assertThat(section.items()).hasSize(PREVIEW_SIZE);
        assertThat(section.items()).allSatisfy(item -> {
            assertThat(item.id()).matches("[1-9][0-9]*");
            assertThat(item.authorId()).matches("[1-9][0-9]*");
            assertThat(item.title()).isNotBlank();
            assertThat(item.createdAt()).isNotNull();
            assertThat(item.coverUrl()).isNotNull();
        });
    }

    private OldResponseMeasure measureOldResponses(int sectionCount) throws Exception {
        long utf8Bytes = 0L;
        long gzipBytes = 0L;
        for (Long sectionId : normalSectionIds.subList(0, sectionCount)) {
            PageResponse<ThreadDto> oldResponse = PageResponse.from(
                    threadSearchService.list(null, null, sectionId, 1, PREVIEW_SIZE));
            byte[] raw = objectMapper.writeValueAsBytes(oldResponse);
            utf8Bytes += raw.length;
            gzipBytes += gzip(raw).length;
        }
        return new OldResponseMeasure(utf8Bytes, gzipBytes);
    }

    private Section persistSection(int index) {
        Section section = new Section();
        section.setName("Section preview " + index);
        section.setSlug("section-preview-" + index);
        entityManager.persist(section);
        entityManager.flush();
        return section;
    }

    private User persistUser(String username, String phone) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPhone(phone);
        user.setPasswordHash("test-only-hash");
        user.setCreatedAt(OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        entityManager.persist(user);
        entityManager.flush();
        return user;
    }

    private Long persistThread(Long sectionId, int sectionIndex, int threadIndex,
                               Long threadAuthorId, Instant createdAt) {
        Thread thread = new Thread();
        thread.setSectionId(sectionId);
        thread.setAuthorId(threadAuthorId);
        thread.setTitle("section-" + sectionIndex + "-thread-" + threadIndex);
        thread.setContentMd(content(sectionIndex, threadIndex));
        thread.setStatus("NORMAL");
        thread.setCreatedAt(createdAt);
        thread.setUpdatedAt(createdAt);
        entityManager.persist(thread);
        entityManager.flush();
        return thread.getId();
    }

    private void persistThreadWithStatus(Long sectionId, String title, String status, Instant createdAt) {
        Thread thread = new Thread();
        thread.setSectionId(sectionId);
        thread.setAuthorId(authorId);
        thread.setTitle(title);
        thread.setContentMd(content(0, 0));
        thread.setStatus(status);
        thread.setCreatedAt(createdAt);
        thread.setUpdatedAt(createdAt);
        entityManager.persist(thread);
        entityManager.flush();
    }

    private static String content(int sectionIndex, int threadIndex) {
        String cover;
        if (sectionIndex == 0 && threadIndex == 3) {
            cover = "<img class=\"cover\" src=\"/uploads/html-cover.png\">\n";
        } else if (sectionIndex == 0 && threadIndex == 2) {
            cover = "no cover image\n";
        } else {
            cover = "![cover](https://cdn.example.test/section-preview/"
                    + sectionIndex + "-" + threadIndex + ".png)\n";
        }
        StringBuilder value = new StringBuilder(CONTENT_BYTES);
        value.append(cover).append("# section-").append(sectionIndex)
                .append("-thread-").append(threadIndex).append("\n\n");
        while (value.length() < CONTENT_BYTES) {
            value.append("baseline markdown body for section preview integration evidence. ");
        }
        value.setLength(CONTENT_BYTES);
        return value.toString();
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(raw);
        }
        return output.toByteArray();
    }

    private record OldResponseMeasure(long utf8Bytes, long gzipBytes) {
    }

}
