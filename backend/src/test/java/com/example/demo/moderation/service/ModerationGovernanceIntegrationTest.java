package com.example.demo.moderation.service;

import com.example.demo.governance.service.GovernanceService;
import com.example.demo.moderation.dto.CreateReportRequest;
import com.example.demo.moderation.dto.ResolveReportRequest;
import com.example.demo.moderation.model.*;
import com.example.demo.posts.dto.CreatePostRequest;
import com.example.demo.posts.entity.Post;
import com.example.demo.posts.repo.PostRepository;
import com.example.demo.posts.service.PostService;
import com.example.demo.sections.entity.Section;
import com.example.demo.sections.repo.SectionRepository;
import com.example.demo.threads.dto.CreateThreadRequest;
import com.example.demo.threads.dto.UpdateThreadRequest;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.threads.service.ThreadService;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisabledInAotMode
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:m4-governance-content;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false"
})
class ModerationGovernanceIntegrationTest {
    @Autowired GovernanceService governance;
    @Autowired ContentReportService reports;
    @Autowired ThreadService threads;
    @Autowired PostService posts;
    @Autowired ThreadRepository threadRows;
    @Autowired PostRepository postRows;
    @Autowired SectionRepository sections;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    Long admin;
    Long owner;
    Long moderator;
    Section section;

    @BeforeEach
    void setup() {
        admin = user("gov-admin", "13900001101");
        owner = user("gov-author", "13900001102");
        moderator = user("gov-moderator", "13900001103");
        governance.bootstrapAdmin(admin);
        section = section();
    }

    @AfterEach
    void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test
    void hideAndRestoreAreAuditedVersionedAndIdempotent() {
        Thread thread = thread(section);
        Long report = report(ReportTargetType.THREAD, thread.getId());
        reports.resolve(admin, report, resolution(ReportAction.HIDE));
        assertStatus(404, () -> threads.getById(thread.getId()));
        assertThat(threadRows.findById(thread.getId()).orElseThrow().getContentVersion()).isEqualTo(2);
        reports.resolve(admin, report, resolution(ReportAction.RESTORE));
        reports.resolve(admin, report, resolution(ReportAction.RESTORE));
        assertThat(threads.getById(thread.getId()).getId()).isEqualTo(thread.getId());
        assertThat(threadRows.findById(thread.getId()).orElseThrow().getContentVersion()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM community_audit WHERE target_type='THREAD' AND target_id=?",
                Long.class, thread.getId())).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT event_type FROM content_event_outbox WHERE aggregate_id=? ORDER BY aggregate_version",
                String.class, thread.getId())).containsExactly("THREAD_HIDDEN", "THREAD_UPSERTED");
    }

    @Test
    void scopedReportsAndRevocationUseCurrentPermissions() throws Exception {
        Section outside = section();
        Long ownReport = report(ReportTargetType.THREAD, thread(section).getId());
        Long otherReport = report(ReportTargetType.THREAD, thread(outside).getId());
        adminPut("/users/" + moderator + "/moderator-sections",
                "{\"sectionIds\":[" + section.getId() + "],\"reason\":\"负责本分区\"}");
        assertThat(reports.listForReviewer(moderator, ReportStatus.PENDING, 1, 20).getContent())
                .extracting(value -> value.getId()).containsExactly(ownReport);
        assertStatus(403, () -> reports.resolve(moderator, otherReport, resolution(ReportAction.HIDE)));
        reports.resolve(moderator, ownReport, resolution(ReportAction.HIDE));
        adminPut("/users/" + moderator + "/moderator-sections", "{\"sectionIds\":[],\"reason\":\"撤销授权\"}");
        assertStatus(403, () -> reports.resolve(moderator, ownReport, resolution(ReportAction.RESTORE)));
        assertStatus(403, () -> reports.listForReviewer(moderator, ReportStatus.PENDING, 1, 20));
    }

    @Test
    void authorDeletionCannotBeRestoredByModeration() {
        Thread thread = thread(section);
        Long report = report(ReportTargetType.THREAD, thread.getId());
        reports.resolve(admin, report, resolution(ReportAction.HIDE));
        threads.delete(owner, thread.getId());
        assertStatus(409, () -> reports.resolve(admin, report, resolution(ReportAction.RESTORE)));
        assertThat(threadRows.findById(thread.getId()).orElseThrow().getStatus()).isEqualTo("DELETED");
    }

    @Test
    void commentRestoreRequiresNormalParentAndDoesNotLeaveFalseAudit() {
        Thread thread = thread(section);
        Post post = new Post();
        post.setThreadId(thread.getId()); post.setAuthorId(owner); post.setContentMd("reply"); post.setStatus("NORMAL");
        post = postRows.save(post);
        Long postReport = report(ReportTargetType.POST, post.getId());
        reports.resolve(admin, postReport, resolution(ReportAction.HIDE));
        Long threadReport = report(ReportTargetType.THREAD, thread.getId());
        reports.resolve(admin, threadReport, resolution(ReportAction.HIDE));
        assertStatus(404, () -> reports.resolve(admin, postReport, resolution(ReportAction.RESTORE)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM community_audit WHERE target_type='POST' AND target_id=?",
                Long.class, post.getId())).isEqualTo(1);
        reports.resolve(admin, threadReport, resolution(ReportAction.RESTORE));
        reports.resolve(admin, postReport, resolution(ReportAction.RESTORE));
        assertThat(postRows.findById(post.getId()).orElseThrow().getStatus()).isEqualTo("NORMAL");
    }

    @Test
    void restrictionAndArchiveProtectWritesWhileKeepingReadsAndAuthorDeletion() throws Exception {
        Thread thread = thread(section);
        adminPut("/users/" + owner + "/posting-restriction", "{\"restricted\":true,\"until\":null,\"reason\":\"暂停发言\"}");
        assertStatus(403, () -> threads.create(owner, createThread()));
        CreatePostRequest reply = new CreatePostRequest(); reply.setContentMd("reply");
        assertStatus(403, () -> posts.create(owner, thread.getId(), reply));
        UpdateThreadRequest edit = new UpdateThreadRequest(); edit.setTitle("edited");
        assertStatus(403, () -> threads.update(owner, thread.getId(), edit));
        assertThat(threads.getById(thread.getId()).getId()).isEqualTo(thread.getId());
        adminPut("/users/" + owner + "/posting-restriction", "{\"restricted\":false,\"until\":null,\"reason\":\"恢复发言\"}");
        Long postId = posts.create(owner, thread.getId(), reply).getId();
        adminPut("/sections/" + section.getId(), "{\"name\":\"历史交流\",\"slug\":\"" + section.getSlug()
                + "\",\"description\":\"\",\"rules\":\"\",\"visible\":true,\"archived\":true,\"reason\":\"归档\"}");
        assertStatus(403, () -> threads.create(owner, createThread()));
        assertStatus(403, () -> threads.update(owner, thread.getId(), edit));
        assertStatus(403, () -> posts.create(owner, thread.getId(), reply));
        assertThat(threads.getById(thread.getId()).getId()).isEqualTo(thread.getId());
        posts.delete(owner, postId);
        assertThat(postRows.existsById(postId)).isFalse();
    }

    private void adminPut(String path, String body) throws Exception {
        var authentication = new TestingAuthenticationToken(admin, null, "ROLE_USER");
        mvc.perform(put("/api/v1/governance" + path).principal(authentication)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private Long user(String name, String phone) {
        return users.findByUsername(name).orElseGet(() -> {
            User user = new User(); user.setUsername(name); user.setEmail(name + "@example.test");
            user.setPhone(phone); user.setPasswordHash("test-only-hash"); return users.save(user);
        }).getId();
    }

    private Section section() {
        Section value = new Section(); value.setName("交流"); value.setSlug("m4-" + UUID.randomUUID());
        return sections.save(value);
    }

    private Thread thread(Section target) {
        Thread value = new Thread(); value.setAuthorId(owner); value.setSectionId(target.getId());
        value.setTitle("审核与恢复"); value.setContentMd("content"); value.setContentVersion(1L);
        value.setStatus("NORMAL"); return threadRows.save(value);
    }

    private Long report(ReportTargetType type, Long target) {
        CreateReportRequest request = new CreateReportRequest(); request.setTargetType(type);
        request.setTargetId(target); request.setReason(ReportReason.SPAM); return reports.create(owner, request).getId();
    }

    private ResolveReportRequest resolution(ReportAction action) {
        ResolveReportRequest request = new ResolveReportRequest(); request.setAction(action);
        request.setReason("审核说明"); return request;
    }

    private CreateThreadRequest createThread() {
        CreateThreadRequest request = new CreateThreadRequest(); request.setSectionId(section.getId());
        request.setTitle("new"); request.setContent("content"); return request;
    }

    private static void assertStatus(int expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(expected));
    }
}
