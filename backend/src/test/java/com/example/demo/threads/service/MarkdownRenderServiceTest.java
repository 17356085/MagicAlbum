package com.example.demo.threads.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

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

    @Test
    void renderWithCache_should_expire_entries_after_ttl() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-18T00:00:00Z"));
        MarkdownRenderService service = new MarkdownRenderService(clock);
        Instant updatedAt = clock.instant();

        service.renderWithCache(1L, updatedAt, "# Before");
        assertThat(service.containsCached(1L)).isTrue();

        clock.advance(MarkdownRenderService.CACHE_TTL);
        String refreshed = service.renderWithCache(1L, updatedAt, "# After");

        assertThat(refreshed).contains("After");
        assertThat(service.containsCached(1L)).isTrue();
        assertThat(service.cacheSize()).isEqualTo(1);
    }

    @Test
    void renderWithCache_should_bound_lru_entry_count() {
        MarkdownRenderService service = new MarkdownRenderService();
        Instant updatedAt = Instant.parse("2026-10-18T00:00:00Z");

        for (long id = 1; id <= MarkdownRenderService.MAX_CACHE_ENTRIES + 1L; id++) {
            service.renderWithCache(id, updatedAt, "item-" + id);
        }

        assertThat(service.cacheSize()).isEqualTo(MarkdownRenderService.MAX_CACHE_ENTRIES);
        assertThat(service.containsCached(1L)).isFalse();
        assertThat(service.containsCached((long) MarkdownRenderService.MAX_CACHE_ENTRIES + 1L)).isTrue();
    }

    @Test
    void renderWithCache_should_skip_single_item_over_total_html_limit() {
        MarkdownRenderService service = new MarkdownRenderService();
        Instant updatedAt = Instant.parse("2026-10-18T00:00:00Z");

        service.renderWithCache(1L, updatedAt,
                "x".repeat(MarkdownRenderService.MAX_TOTAL_HTML_CHARS + 1));

        assertThat(service.cacheSize()).isZero();
        assertThat(service.cachedHtmlCharacters()).isZero();
        assertThat(service.containsCached(1L)).isFalse();
    }

    @Test
    void renderWithCache_should_bound_total_html_characters() {
        MarkdownRenderService service = new MarkdownRenderService();
        Instant updatedAt = Instant.parse("2026-10-18T00:00:00Z");
        String markdown = "x".repeat(900_000);

        service.renderWithCache(1L, updatedAt, markdown);
        service.renderWithCache(2L, updatedAt, markdown);
        service.renderWithCache(3L, updatedAt, markdown);

        assertThat(service.cachedHtmlCharacters())
                .isLessThanOrEqualTo(MarkdownRenderService.MAX_TOTAL_HTML_CHARS);
        assertThat(service.cacheSize()).isLessThanOrEqualTo(2);
    }

    @Test
    void evict_should_remove_cached_html_and_release_size() {
        MarkdownRenderService service = new MarkdownRenderService();
        service.renderWithCache(1L, Instant.parse("2026-10-18T00:00:00Z"), "# Title");

        service.evict(1L);

        assertThat(service.cacheSize()).isZero();
        assertThat(service.cachedHtmlCharacters()).isZero();
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}

