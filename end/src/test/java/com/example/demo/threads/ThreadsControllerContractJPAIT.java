package com.example.demo.threads;

import com.example.demo.sections.entity.Section;
import com.example.demo.sections.repo.SectionRepository;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@DisabledInAotMode
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ba_jpa;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.driverClassName=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "feature.mp.read.threads=false"
})
public class ThreadsControllerContractJPAIT {

    @Autowired MockMvc mockMvc;
    @Autowired ThreadRepository threadRepository;
    @Autowired SectionRepository sectionRepository;
    @Autowired UserRepository userRepository;

    @BeforeEach
    void setupData() {
        threadRepository.deleteAll();
        sectionRepository.deleteAll();
        userRepository.deleteAll();

        Section s = new Section();
        s.setName("Tech");
        s.setSlug("tech");
        s = sectionRepository.save(s);

        User u = new User();
        u.setUsername("alice");
        u.setEmail("alice@example.com");
        u.setPhone("13000000000");
        u.setPasswordHash("hash");
        u = userRepository.save(u);

        Thread t1 = new Thread();
        t1.setSectionId(s.getId());
        t1.setAuthorId(u.getId());
        t1.setTitle("T1");
        t1.setContentMd("C1");
        t1.setStatus("NORMAL");
        t1.setCreatedAt(Instant.parse("2025-01-01T00:00:01Z"));
        t1.setUpdatedAt(Instant.parse("2025-01-01T00:00:02Z"));
        threadRepository.save(t1);

        Thread t2 = new Thread();
        t2.setSectionId(s.getId());
        t2.setAuthorId(u.getId());
        t2.setTitle("T2");
        t2.setContentMd("C2");
        t2.setStatus("NORMAL");
        t2.setCreatedAt(Instant.parse("2025-01-02T00:00:01Z"));
        t2.setUpdatedAt(Instant.parse("2025-01-02T00:00:02Z"));
        threadRepository.save(t2);

        Thread t3 = new Thread();
        t3.setSectionId(s.getId());
        t3.setAuthorId(u.getId());
        t3.setTitle("T3");
        t3.setContentMd("C3");
        t3.setStatus("NORMAL");
        t3.setCreatedAt(Instant.parse("2025-01-03T00:00:01Z"));
        t3.setUpdatedAt(Instant.parse("2025-01-03T00:00:02Z"));
        threadRepository.save(t3);
    }

    @Test
    void list_contract_should_match_and_order_by_createdAt_desc() throws Exception {
        String resp = mockMvc.perform(get("/api/v1/threads")
                        .param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(resp);
        assertThat(root.has("items")).isTrue();
        assertThat(root.get("page").asInt()).isEqualTo(1);
        assertThat(root.get("size").asInt()).isEqualTo(10);
        assertThat(root.get("total").asLong()).isEqualTo(3);

        JsonNode items = root.get("items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.size()).isEqualTo(3);
        // createdAt DESC => T3 first
        assertThat(items.get(0).get("title").asText()).isEqualTo("T3");
        assertThat(items.get(0).get("sectionName").asText()).isEqualTo("Tech");
        assertThat(items.get(0).get("authorUsername").asText()).isEqualTo("alice");
    }
}