package com.example.demo.threads.service;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MarkdownRenderService {
    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer = HtmlRenderer.builder().build();

    private static class CacheEntry {
        final Instant updatedAt;
        final String html;
        CacheEntry(Instant updatedAt, String html) {
            this.updatedAt = updatedAt;
            this.html = html;
        }
    }

    private final ConcurrentHashMap<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    public String render(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";
        return renderer.render(parser.parse(markdown));
    }

    public String renderWithCache(Long id, Instant updatedAt, String markdown) {
        if (id == null || updatedAt == null) return render(markdown);
        CacheEntry existing = cache.get(id);
        if (existing != null && updatedAt.equals(existing.updatedAt)) {
            return existing.html;
        }
        String html = render(markdown);
        cache.put(id, new CacheEntry(updatedAt, html));
        return html;
    }
}