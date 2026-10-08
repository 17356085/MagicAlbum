package com.example.demo.bookmarks;

import com.example.demo.bookmarks.entity.ThreadBookmark;
import com.example.demo.bookmarks.repo.ThreadBookmarkRepository;
import com.example.demo.sections.entity.Section;
import com.example.demo.sections.repo.SectionRepository;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@DisabledInAotMode
@MockitoBean(types = StringRedisTemplate.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:bookmark_it;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false",
        "spring.datasource.driverClassName=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "feature.mp.read.threads=false"
})
class ThreadBookmarkControllerIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ThreadBookmarkRepository bookmarkRepository;

    @Autowired
    ThreadRepository threadRepository;

    @Autowired
    SectionRepository sectionRepository;

    @Autowired
    UserRepository userRepository;

    private User firstUser;
    private User secondUser;
    private Section section;
    private Thread normalThread;
    private Thread secondNormalThread;
    private Thread hiddenThread;
    private Thread deletedThread;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        bookmarkRepository.deleteAll();
        threadRepository.deleteAll();
        sectionRepository.deleteAll();
        userRepository.deleteAll();

        section = new Section();
        section.setName("Reading");
        section.setSlug("reading");
        section = sectionRepository.save(section);

        firstUser = saveUser("bookmark-owner", "bookmark-owner@example.com", "13000000001");
        secondUser = saveUser("other-reader", "other-reader@example.com", "13000000002");

        normalThread = saveThread("Normal one", "NORMAL");
        secondNormalThread = saveThread("Normal two", "NORMAL");
        hiddenThread = saveThread("Hidden thread", "HIDDEN");
        deletedThread = saveThread("Deleted thread", "DELETED");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void bookmarkIsPersistedIdempotentlyAndIsolatedPerUser() throws Exception {
        performAs(firstUser, post(bookmarkPath(normalThread)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookmarked").value(true));
        performAs(firstUser, post(bookmarkPath(normalThread)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookmarked").value(true));

        assertThat(bookmarkRepository.findByUserIdAndThreadId(firstUser.getId(), normalThread.getId()))
                .isPresent();
        assertThat(bookmarkRepository.findAll()).hasSize(1);

        performAs(secondUser, get(bookmarkPath(normalThread)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookmarked").value(false));

        performAs(firstUser, delete(bookmarkPath(normalThread)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookmarked").value(false));
        performAs(firstUser, delete(bookmarkPath(normalThread)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookmarked").value(false));
        assertThat(bookmarkRepository.findByUserIdAndThreadId(firstUser.getId(), normalThread.getId()))
                .isEmpty();
    }

    @Test
    void getAndPostRejectNonNormalThreadsAndListHidesThem() throws Exception {
        performAs(firstUser, get(bookmarkPath(hiddenThread)))
                .andExpect(status().isNotFound());
        performAs(firstUser, post(bookmarkPath(hiddenThread)))
                .andExpect(status().isNotFound());
        performAs(firstUser, get(bookmarkPath(deletedThread)))
                .andExpect(status().isNotFound());
        performAs(firstUser, post(bookmarkPath(deletedThread)))
                .andExpect(status().isNotFound());

        saveBookmark(firstUser, normalThread, Instant.parse("2026-10-07T00:00:03Z"));
        saveBookmark(firstUser, hiddenThread, Instant.parse("2026-10-07T00:00:02Z"));
        saveBookmark(firstUser, deletedThread, Instant.parse("2026-10-07T00:00:01Z"));

        String response = performAs(firstUser, get("/api/v1/users/me/bookmarks"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode root = new ObjectMapper().readTree(response);

        assertThat(root.get("total").asLong()).isEqualTo(1);
        assertThat(root.get("items")).hasSize(1);
        assertThat(root.get("items").get(0).get("threadId").asLong()).isEqualTo(normalThread.getId());
    }

    @Test
    void listUsesBookmarkTimeThenThreadIdAndReportsClampedPageSize() throws Exception {
        saveBookmark(firstUser, normalThread, Instant.parse("2026-10-07T00:00:02Z"));
        saveBookmark(firstUser, secondNormalThread, Instant.parse("2026-10-07T00:00:02Z"));

        String response = performAs(firstUser, get("/api/v1/users/me/bookmarks")
                        .param("page", "1")
                        .param("size", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].sectionName").value("Reading"))
                .andExpect(jsonPath("$.items[0].bookmarkedAt").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode items = new ObjectMapper().readTree(response).get("items");

        assertThat(items.get(0).get("threadId").asLong()).isEqualTo(secondNormalThread.getId());
        assertThat(items.get(1).get("threadId").asLong()).isEqualTo(normalThread.getId());
    }

    @Test
    void bookmarkEndpointsRequireLongPrincipal() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(get(bookmarkPath(normalThread)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users/me/bookmarks"))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions performAs(User user, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(asUser(user)));
    }

    private RequestPostProcessor asUser(User user) {
        return request -> {
            TestingAuthenticationToken authentication = new TestingAuthenticationToken(user.getId(), null);
            authentication.setAuthenticated(true);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            return request;
        };
    }

    private User saveUser(String username, String email, String phone) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPhone(phone);
        user.setPasswordHash("test-hash");
        return userRepository.save(user);
    }

    private Thread saveThread(String title, String status) {
        Thread thread = new Thread();
        thread.setSectionId(section.getId());
        thread.setAuthorId(firstUser.getId());
        thread.setTitle(title);
        thread.setContentMd("content");
        thread.setStatus(status);
        return threadRepository.save(thread);
    }

    private void saveBookmark(User user, Thread thread, Instant bookmarkedAt) {
        ThreadBookmark bookmark = new ThreadBookmark();
        bookmark.setUserId(user.getId());
        bookmark.setThreadId(thread.getId());
        bookmark.setCreatedAt(bookmarkedAt);
        bookmarkRepository.saveAndFlush(bookmark);
    }

    private String bookmarkPath(Thread thread) {
        return "/api/v1/threads/" + thread.getId() + "/bookmark";
    }
}
