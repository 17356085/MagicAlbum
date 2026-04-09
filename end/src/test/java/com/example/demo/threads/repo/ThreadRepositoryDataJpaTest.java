package com.example.demo.threads.repo;

import com.example.demo.threads.entity.Thread;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisabledInAotMode
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ba_repo;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.driverClassName=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class ThreadRepositoryDataJpaTest {

    @Autowired
    ThreadRepository threadRepository;

    @BeforeEach
    void cleanup() {
        threadRepository.deleteAll();
    }

    @Test
    void searchNewest_should_filter_by_status_and_order_by_createdAt_desc() {
        Thread normalOld = new Thread();
        normalOld.setSectionId(1L);
        normalOld.setAuthorId(1L);
        normalOld.setTitle("Old");
        normalOld.setContentMd("hello");
        normalOld.setStatus("NORMAL");
        normalOld.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
        normalOld.setUpdatedAt(Instant.parse("2025-01-01T00:00:00Z"));
        threadRepository.save(normalOld);

        Thread deletedNew = new Thread();
        deletedNew.setSectionId(1L);
        deletedNew.setAuthorId(1L);
        deletedNew.setTitle("New but deleted");
        deletedNew.setContentMd("hello");
        deletedNew.setStatus("DELETED");
        deletedNew.setCreatedAt(Instant.parse("2025-01-03T00:00:00Z"));
        deletedNew.setUpdatedAt(Instant.parse("2025-01-03T00:00:00Z"));
        threadRepository.save(deletedNew);

        Thread normalNew = new Thread();
        normalNew.setSectionId(1L);
        normalNew.setAuthorId(1L);
        normalNew.setTitle("New");
        normalNew.setContentMd("world");
        normalNew.setStatus("NORMAL");
        normalNew.setCreatedAt(Instant.parse("2025-01-02T00:00:00Z"));
        normalNew.setUpdatedAt(Instant.parse("2025-01-02T00:00:00Z"));
        threadRepository.save(normalNew);

        Page<Thread> page = threadRepository.searchNewest(null, null, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent().get(0).getTitle()).isEqualTo("New");
        assertThat(page.getContent().get(1).getTitle()).isEqualTo("Old");
    }

    @Test
    void searchNewest_should_support_q_filter_case_insensitive() {
        Thread t1 = new Thread();
        t1.setSectionId(1L);
        t1.setAuthorId(1L);
        t1.setTitle("Hello Java");
        t1.setContentMd("abc");
        t1.setStatus("NORMAL");
        t1.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
        t1.setUpdatedAt(Instant.parse("2025-01-01T00:00:00Z"));
        threadRepository.save(t1);

        Thread t2 = new Thread();
        t2.setSectionId(1L);
        t2.setAuthorId(1L);
        t2.setTitle("Other");
        t2.setContentMd("Vue");
        t2.setStatus("NORMAL");
        t2.setCreatedAt(Instant.parse("2025-01-02T00:00:00Z"));
        t2.setUpdatedAt(Instant.parse("2025-01-02T00:00:00Z"));
        threadRepository.save(t2);

        Page<Thread> page = threadRepository.searchNewest("java", null, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getTitle()).isEqualTo("Hello Java");
    }
}
