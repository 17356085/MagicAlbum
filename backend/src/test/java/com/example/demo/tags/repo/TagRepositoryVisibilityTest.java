package com.example.demo.tags.repo;

import com.example.demo.sections.entity.Section;
import com.example.demo.sections.repo.SectionRepository;
import com.example.demo.tags.dto.TagStatsView;
import com.example.demo.tags.entity.Tag;
import com.example.demo.tags.entity.ThreadTag;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisabledInAotMode
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ba_tag_repository_visibility;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.driverClassName=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class TagRepositoryVisibilityTest {

    @Autowired TagRepository tagRepository;
    @Autowired SectionRepository sectionRepository;
    @Autowired ThreadRepository threadRepository;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;

    private Long sectionA;
    private Long sectionB;

    @BeforeEach
    void seedData() {
        entityManager.createQuery("DELETE FROM ThreadTag").executeUpdate();
        tagRepository.deleteAll();
        threadRepository.deleteAll();
        sectionRepository.deleteAll();
        userRepository.deleteAll();
        entityManager.flush();
        entityManager.clear();

        sectionA = saveSection("tag-section-a", "Tag section A");
        sectionB = saveSection("tag-section-b", "Tag section B");
        User author = saveUser();

        Tag alpha = saveTag("alpha", "thread");
        Tag beta = saveTag("beta", "thread");
        Tag nonThreadAlpha = saveTag("alpha", "other");

        Thread a1 = saveThread(sectionA, author.getId(), "A1", "NORMAL");
        Thread a2 = saveThread(sectionA, author.getId(), "A2", "NORMAL");
        Thread aHidden = saveThread(sectionA, author.getId(), "A hidden", "HIDDEN");
        Thread aDeleted = saveThread(sectionA, author.getId(), "A deleted", "DELETED");
        Thread a3 = saveThread(sectionA, author.getId(), "A3", "NORMAL");
        Thread b1 = saveThread(sectionB, author.getId(), "B1", "NORMAL");
        Thread b2 = saveThread(sectionB, author.getId(), "B2", "NORMAL");

        link(a1, alpha);
        link(a1, nonThreadAlpha);
        link(a2, alpha);
        link(aHidden, alpha);
        link(aDeleted, alpha);
        link(a3, beta);
        link(b1, alpha);
        link(b2, beta);
        entityManager.flush();
    }

    @Test
    void findPopular_should_filter_type_and_status_scope_by_section_and_apply_limit_order() {
        List<TagStatsView> all = tagRepository.findPopular(null, PageRequest.of(0, 10));
        assertThat(all).extracting(TagStatsView::getName).containsExactly("alpha", "beta");
        assertThat(all).extracting(TagStatsView::getType).containsExactly("thread", "thread");
        assertThat(all).extracting(TagStatsView::getThreadCount).containsExactly(3L, 2L);

        List<TagStatsView> section = tagRepository.findPopular(sectionA, PageRequest.of(0, 10));
        assertThat(section).extracting(TagStatsView::getName).containsExactly("alpha", "beta");
        assertThat(section).extracting(TagStatsView::getThreadCount).containsExactly(2L, 1L);

        List<TagStatsView> tied = tagRepository.findPopular(sectionB, PageRequest.of(0, 10));
        assertThat(tied).extracting(TagStatsView::getName).containsExactly("alpha", "beta");
        assertThat(tied).extracting(TagStatsView::getThreadCount).containsExactly(1L, 1L);

        List<TagStatsView> limited = tagRepository.findPopular(null, PageRequest.of(0, 1));
        assertThat(limited).extracting(TagStatsView::getName).containsExactly("alpha");
    }

    @Test
    void search_should_match_popular_visibility_scope_and_exclude_non_thread_type() {
        Page<TagStatsView> all = tagRepository.search(null, null, PageRequest.of(0, 10));
        assertThat(all.getTotalElements()).isEqualTo(2L);
        assertThat(all.getContent()).extracting(TagStatsView::getName).containsExactly("alpha", "beta");
        assertThat(all.getContent()).extracting(TagStatsView::getThreadCount).containsExactly(3L, 2L);

        Page<TagStatsView> alpha = tagRepository.search("ALPHA", null, PageRequest.of(0, 10));
        assertThat(alpha.getTotalElements()).isEqualTo(1L);
        assertThat(alpha.getContent()).extracting(TagStatsView::getName).containsExactly("alpha");
        assertThat(alpha.getContent()).extracting(TagStatsView::getType).containsExactly("thread");

        Page<TagStatsView> section = tagRepository.search(null, sectionA, PageRequest.of(0, 10));
        assertThat(section.getTotalElements()).isEqualTo(2L);
        assertThat(section.getContent()).extracting(TagStatsView::getName).containsExactly("alpha", "beta");
        assertThat(section.getContent()).extracting(TagStatsView::getThreadCount).containsExactly(2L, 1L);
    }

    private Long saveSection(String slug, String name) {
        Section section = new Section();
        section.setSlug(slug);
        section.setName(name);
        return sectionRepository.saveAndFlush(section).getId();
    }

    private User saveUser() {
        User user = new User();
        user.setUsername("tag-repository-user");
        user.setEmail("tag-repository-user@example.com");
        user.setPhone("13000000999");
        user.setPasswordHash("hash");
        return userRepository.saveAndFlush(user);
    }

    private Tag saveTag(String name, String type) {
        Tag tag = new Tag();
        tag.setName(name);
        tag.setType(type);
        return tagRepository.saveAndFlush(tag);
    }

    private Thread saveThread(Long sectionId, Long authorId, String title, String status) {
        Thread thread = new Thread();
        thread.setSectionId(sectionId);
        thread.setAuthorId(authorId);
        thread.setTitle(title);
        thread.setContentMd(title + " content");
        thread.setStatus(status);
        Instant createdAt = Instant.parse("2025-01-01T00:00:00Z").plusSeconds(title.hashCode());
        thread.setCreatedAt(createdAt);
        thread.setUpdatedAt(createdAt);
        return threadRepository.saveAndFlush(thread);
    }

    private void link(Thread thread, Tag tag) {
        ThreadTag relation = new ThreadTag();
        relation.setThreadId(thread.getId());
        relation.setTagId(tag.getId());
        entityManager.persist(relation);
    }
}
