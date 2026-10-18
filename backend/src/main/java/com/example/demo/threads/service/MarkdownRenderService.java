package com.example.demo.threads.service;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class MarkdownRenderService {
    static final int MAX_CACHE_ENTRIES = 256;
    static final int MAX_TOTAL_HTML_CHARS = 2_000_000;
    static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer = HtmlRenderer.builder()
            .escapeHtml(true)
            .build();
    private final Clock clock;
    private final Object cacheLock = new Object();
    private final LinkedHashMap<Long, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true);
    private int cachedHtmlChars;

    private static class CacheEntry {
        final Instant updatedAt;
        final Instant cachedAt;
        final String html;
        CacheEntry(Instant updatedAt, Instant cachedAt, String html) {
            this.updatedAt = updatedAt;
            this.cachedAt = cachedAt;
            this.html = html;
        }
    }

    public MarkdownRenderService() {
        this(Clock.systemUTC());
    }

    public MarkdownRenderService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String render(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";
        return renderer.render(parser.parse(markdown));
    }

    public String renderWithCache(Long id, Instant updatedAt, String markdown) {
        if (id == null || updatedAt == null) return render(markdown);

        Instant now = clock.instant();
        synchronized (cacheLock) {
            purgeExpired(now);
            CacheEntry existing = cache.get(id);
            if (existing != null && updatedAt.equals(existing.updatedAt)) {
                return existing.html;
            }
        }

        String html = render(markdown);
        if (html.length() > MAX_TOTAL_HTML_CHARS) {
            synchronized (cacheLock) {
                purgeExpired(now);
                CacheEntry existing = cache.get(id);
                if (existing == null || !existing.updatedAt.isAfter(updatedAt)) {
                    removeEntry(id);
                }
            }
            return html;
        }

        synchronized (cacheLock) {
            purgeExpired(now);
            CacheEntry existing = cache.get(id);
            if (existing != null) {
                if (updatedAt.equals(existing.updatedAt)) {
                    return existing.html;
                }
                // A concurrent render may have installed a newer version while this one parsed.
                if (existing.updatedAt.isAfter(updatedAt)) {
                    return html;
                }
            }
            if (existing != null) {
                removeEntry(id);
            }
            CacheEntry entry = new CacheEntry(updatedAt, now, html);
            cache.put(id, entry);
            cachedHtmlChars += html.length();
            trimToBounds();
        }
        return html;
    }

    public void evict(Long id) {
        if (id == null) {
            return;
        }
        synchronized (cacheLock) {
            removeEntry(id);
        }
    }

    int cacheSize() {
        synchronized (cacheLock) {
            return cache.size();
        }
    }

    int cachedHtmlCharacters() {
        synchronized (cacheLock) {
            return cachedHtmlChars;
        }
    }

    boolean containsCached(Long id) {
        synchronized (cacheLock) {
            return cache.containsKey(id);
        }
    }

    private boolean isExpired(CacheEntry entry, Instant now) {
        return !now.isBefore(entry.cachedAt.plus(CACHE_TTL));
    }

    private void trimToBounds() {
        Iterator<Map.Entry<Long, CacheEntry>> iterator = cache.entrySet().iterator();
        while ((cache.size() > MAX_CACHE_ENTRIES || cachedHtmlChars > MAX_TOTAL_HTML_CHARS)
                && iterator.hasNext()) {
            CacheEntry removed = iterator.next().getValue();
            cachedHtmlChars -= removed.html.length();
            iterator.remove();
        }
    }

    private void purgeExpired(Instant now) {
        Iterator<Map.Entry<Long, CacheEntry>> iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            CacheEntry entry = iterator.next().getValue();
            if (isExpired(entry, now)) {
                cachedHtmlChars -= entry.html.length();
                iterator.remove();
            }
        }
    }

    private void removeEntry(Long id) {
        CacheEntry removed = cache.remove(id);
        if (removed != null) {
            cachedHtmlChars -= removed.html.length();
        }
    }
}
