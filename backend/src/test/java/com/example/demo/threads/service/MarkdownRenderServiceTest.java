package com.example.demo.threads.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownRenderServiceTest {

    @Test
    void render_should_handle_blank() {
        MarkdownRenderService service = new MarkdownRenderService();
        assertThat(service.render(null)).isEqualTo("");
        assertThat(service.render("")).isEqualTo("");
        assertThat(service.render("   ")).isEqualTo("");
    }

    @Test
    void renderWithCache_should_cache_by_id_and_updatedAt() {
        MarkdownRenderService service = new MarkdownRenderService();

        Instant t1 = Instant.parse("2025-01-01T00:00:00Z");
        String html1 = service.renderWithCache(1L, t1, "# Title");
        String html2 = service.renderWithCache(1L, t1, "# Title");
        assertThat(html1).isEqualTo(html2);

        Instant t2 = Instant.parse("2025-01-01T00:00:01Z");
        String html3 = service.renderWithCache(1L, t2, "# Title2");
        assertThat(html3).isNotEqualTo(html2);
    }
}

