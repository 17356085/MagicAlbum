package com.example.demo.threads.controller;

import com.example.demo.posts.entity.Post;
import com.example.demo.posts.repo.PostRepository;
import com.example.demo.sections.entity.Section;
import com.example.demo.sections.repo.SectionRepository;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserProfile;
import com.example.demo.user.repo.UserProfileRepository;
import com.example.demo.user.repo.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisabledInAotMode
class ProjectionContractIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ThreadRepository threadRepository;
    @Autowired PostRepository postRepository;
    @Autowired SectionRepository sectionRepository;
    @Autowired UserRepository userRepository;
    @Autowired UserProfileRepository userProfileRepository;

    private Long threadId;

    @BeforeEach
    void setUp() {
        postRepository.deleteAll();
        threadRepository.deleteAll();
        userProfileRepository.deleteAll();
        userRepository.deleteAll();
        sectionRepository.deleteAll();

        Section section = new Section();
        section.setName("Tech");
        section.setSlug("tech-projection");
        section = sectionRepository.save(section);

        User author = new User();
        author.setUsername("alice");
        author.setEmail("alice@example.com");
        author.setPhone("13000000010");
        author.setPasswordHash("hash");
        author = userRepository.save(author);

        UserProfile authorProfile = new UserProfile();
        authorProfile.setUserId(author.getId());
        authorProfile.setNickname("Alice");
        authorProfile.setAvatarUrl("/uploads/threads/alice.png");
        userProfileRepository.save(authorProfile);

        User parentAuthor = new User();
        parentAuthor.setUsername("bob");
        parentAuthor.setEmail("bob@example.com");
        parentAuthor.setPhone("13000000011");
        parentAuthor.setPasswordHash("hash");
        parentAuthor = userRepository.save(parentAuthor);

        UserProfile parentProfile = new UserProfile();
        parentProfile.setUserId(parentAuthor.getId());
        parentProfile.setNickname("Bob");
        parentProfile.setAvatarUrl("/uploads/threads/bob.png");
        userProfileRepository.save(parentProfile);

        Thread thread = new Thread();
        thread.setSectionId(section.getId());
        thread.setAuthorId(author.getId());
        thread.setTitle("Projection 查询优化");
        thread.setContentMd("正文");
        thread.setStatus("NORMAL");
        thread.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
        thread.setUpdatedAt(Instant.parse("2025-01-01T00:10:00Z"));
        thread = threadRepository.save(thread);
        threadId = thread.getId();

        Post parent = new Post();
        parent.setThreadId(thread.getId());
        parent.setAuthorId(parentAuthor.getId());
        parent.setContentMd("父评论");
        parent.setStatus("NORMAL");
        parent.setCreatedAt(Instant.parse("2025-01-01T00:11:00Z"));
        parent.setUpdatedAt(Instant.parse("2025-01-01T00:11:00Z"));
        parent = postRepository.save(parent);

        Post child = new Post();
        child.setThreadId(thread.getId());
        child.setAuthorId(author.getId());
        child.setContentMd("子评论");
        child.setReplyToPostId(parent.getId());
        child.setStatus("NORMAL");
        child.setCreatedAt(Instant.parse("2025-01-01T00:12:00Z"));
        child.setUpdatedAt(Instant.parse("2025-01-01T00:12:00Z"));
        postRepository.save(child);
    }

    @Test
    void thread_list_should_expose_projection_fields() throws Exception {
        String response = mockMvc.perform(get("/api/v1/threads"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode item = objectMapper.readTree(response).get("items").get(0);
        assertThat(item.get("sectionName").asText()).isEqualTo("Tech");
        assertThat(item.get("authorUsername").asText()).isEqualTo("alice");
        assertThat(item.get("authorNickname").asText()).isEqualTo("Alice");
        assertThat(item.get("authorAvatar").asText()).isEqualTo("/uploads/threads/alice.png");
        assertThat(item.get("title").asText()).isEqualTo("Projection 查询优化");
    }

    @Test
    void post_list_should_expose_projection_fields() throws Exception {
        String response = mockMvc.perform(get("/api/v1/threads/{threadId}/posts", threadId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode items = objectMapper.readTree(response).get("items");
        JsonNode child = items.get(1);
        assertThat(child.get("threadTitle").asText()).isEqualTo("Projection 查询优化");
        assertThat(child.get("authorUsername").asText()).isEqualTo("alice");
        assertThat(child.get("authorNickname").asText()).isEqualTo("Alice");
        assertThat(child.get("authorAvatarUrl").asText()).isEqualTo("/uploads/threads/alice.png");
        assertThat(child.get("parentAuthorUsername").asText()).isEqualTo("bob");
        assertThat(child.get("parentAuthorNickname").asText()).isEqualTo("Bob");
    }
}
