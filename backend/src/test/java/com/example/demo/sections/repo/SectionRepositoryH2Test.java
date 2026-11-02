package com.example.demo.sections.repo;

import com.example.demo.sections.entity.Section;
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
        "spring.datasource.url=jdbc:h2:mem:ba_section_repository;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.driverClassName=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class SectionRepositoryH2Test {

    @Autowired SectionRepository sectionRepository;

    @Test
    void search_should_page_same_created_at_by_id_desc_without_hidden_or_nonmatching_rows() {
        Instant createdAt = Instant.parse("2025-01-01T00:00:00Z");
        Section firstMatch = saveSection("needle-1", "Needle one", true, createdAt);
        Section secondMatch = saveSection("needle-2", "Needle two", true, createdAt);
        Section thirdMatch = saveSection("needle-3", "Needle three", true, createdAt);
        Section fourthMatch = saveSection("needle-4", "Needle four", true, createdAt);
        Section fifthMatch = saveSection("needle-5", "Needle five", true, createdAt);
        Section hiddenMatch = saveSection("needle-hidden", "Needle hidden", false, createdAt);
        Section nonMatchingVisible = saveSection("other", "Other section", true, createdAt);

        Page<Section> firstPage = sectionRepository.search("needle", PageRequest.of(0, 2));
        Page<Section> secondPage = sectionRepository.search("needle", PageRequest.of(1, 2));
        Page<Section> thirdPage = sectionRepository.search("needle", PageRequest.of(2, 2));

        List<Long> ids = List.of(firstPage, secondPage, thirdPage).stream()
                .flatMap(page -> page.getContent().stream())
                .map(Section::getId)
                .toList();
        assertThat(firstPage.getTotalElements()).isEqualTo(5);
        assertThat(secondPage.getTotalElements()).isEqualTo(5);
        assertThat(thirdPage.getTotalElements()).isEqualTo(5);
        assertThat(ids).containsExactly(
                fifthMatch.getId(), fourthMatch.getId(), thirdMatch.getId(),
                secondMatch.getId(), firstMatch.getId());
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).doesNotContain(hiddenMatch.getId(), nonMatchingVisible.getId());
    }

    private Section saveSection(String slug, String name, boolean visible, Instant createdAt) {
        Section section = new Section();
        section.setSlug(slug);
        section.setName(name);
        section.setVisible(visible);
        section.setCreatedAt(createdAt);
        return sectionRepository.saveAndFlush(section);
    }
}
