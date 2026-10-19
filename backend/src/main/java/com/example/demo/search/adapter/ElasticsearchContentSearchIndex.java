package com.example.demo.search.adapter;

import com.example.demo.search.port.ContentSearchIndex;
import com.example.demo.search.port.SearchVersionConflictException;
import com.example.demo.threads.dto.ThreadSearchDocument;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Small Elasticsearch HTTP adapter for the thread search projection.
 *
 * <p>The adapter owns only the Elasticsearch wire contract. It deliberately
 * does not depend on a thread entity or repository, so the SQL projection can
 * remain the source of truth when this adapter is unavailable.</p>
 */
@Component
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true")
public final class ElasticsearchContentSearchIndex implements ContentSearchIndex {
    private static final Pattern MANAGED_INDEX = Pattern.compile(
            "^" + PREFIX + "[a-z0-9-]+$");
    private static final String DEFAULT_URL = "http://127.0.0.1:9200";
    private static final int MAX_SIZE = 10;
    private static final int MAX_FROM = 10_000;

    /**
     * This script is executed for both inserts and updates. The empty upsert
     * lets the script initialize the source exactly once, while later writes
     * compare the event version atomically inside Elasticsearch.
     */
    private static final String UPDATE_SCRIPT =
            "if (ctx._source.version == null) { ctx._source = params.doc; } "
                    + "else if (params.doc.version > ctx._source.version) { ctx._source = params.doc; } "
                    + "else if (params.doc.version == ctx._source.version) { "
                    + "if (params.doc.eventId != ctx._source.eventId "
                    + "|| params.doc.eventType != ctx._source.eventType) { "
                    + "throw new IllegalArgumentException('VERSION_CONFLICT'); } "
                    + "ctx.op = 'noop'; } else { ctx.op = 'noop'; }";

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final Duration readTimeout;
    private final int replicas;
    private final String apiKey;

    @Autowired
    public ElasticsearchContentSearchIndex(
            ObjectMapper objectMapper,
            @Value("${app.search.elasticsearch.url:" + DEFAULT_URL + "}") String url,
            @Value("${app.search.elasticsearch.connect-timeout:1s}") Duration connectTimeout,
            @Value("${app.search.elasticsearch.read-timeout:2s}") Duration readTimeout,
            @Value("${app.search.elasticsearch.replicas:1}") int replicas,
            @Value("${app.search.elasticsearch.api-key:}") String apiKey) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.baseUrl = normalizeBaseUrl(url);
        this.readTimeout = requirePositiveDuration(readTimeout, "readTimeout");
        Duration boundedConnectTimeout = requirePositiveDuration(connectTimeout, "connectTimeout");
        if (this.readTimeout.compareTo(Duration.ofSeconds(5)) > 0
                || boundedConnectTimeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("Elasticsearch HTTP timeouts must be at most five seconds");
        }
        this.apiKey = apiKey == null ? "" : apiKey;
        if (this.apiKey.length() > 4096 || !this.apiKey.matches("[A-Za-z0-9+/=]*")) {
            throw new IllegalArgumentException("Elasticsearch API key format is invalid");
        }
        if (replicas < 0) {
            throw new IllegalArgumentException("replicas must be non-negative");
        }
        this.replicas = replicas;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(boundedConnectTimeout)
                .build();
    }

    public ElasticsearchContentSearchIndex(ObjectMapper mapper, String url, Duration connectTimeout,
                                           Duration readTimeout, int replicas) {
        this(mapper, url, connectTimeout, readTimeout, replicas, "");
    }

    @Override
    public void create(String index) {
        validatePhysicalIndex(index);
        Map<String, Object> settings = mapOf(
                "number_of_shards", 1,
                "number_of_replicas", replicas);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", mapOf("type", "long"));
        properties.put("version", mapOf("type", "long"));
        properties.put("visible", mapOf("type", "boolean"));
        properties.put("title", mapOf("type", "text", "analyzer", "cjk"));
        properties.put("content", mapOf("type", "text", "analyzer", "cjk"));
        properties.put("tags", mapOf("type", "keyword"));
        properties.put("sectionId", mapOf("type", "long"));
        properties.put("createdAt", mapOf("type", "date"));
        properties.put("eventId", mapOf("type", "keyword"));
        properties.put("eventType", mapOf("type", "keyword"));
        Map<String, Object> mappings = new LinkedHashMap<>();
        mappings.put("dynamic", "strict");
        mappings.put("properties", properties);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("settings", settings);
        body.put("mappings", mappings);
        HttpResponse<String> response = send("PUT", "/" + index, json(body));
        requireSuccess(response, "create index");
    }

    @Override
    public void put(String index, ThreadSearchDocument document) {
        validateIndex(index);
        validateDocument(document);
        Map<String, Object> documentBody = toDocumentMap(document);
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("lang", "painless");
        script.put("source", UPDATE_SCRIPT);
        script.put("params", mapOf("doc", documentBody));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scripted_upsert", true);
        body.put("script", script);
        body.put("upsert", Collections.emptyMap());

        String path = "/" + index + "/_update/" + document.id() + "?retry_on_conflict=5";
        HttpResponse<String> response = send("POST", path, json(body));
        if (isVersionConflict(response)) {
            throw new SearchVersionConflictException();
        }
        requireSuccess(response, "update document");
    }

    @Override
    public SearchPage search(String index, String q, String tag, Long sectionId, int page, int size) {
        validateIndex(index);
        int from = validatePage(page, size);
        if (sectionId != null && sectionId <= 0) {
            throw new IllegalArgumentException("sectionId must be positive");
        }
        String normalizedTag = normalizeTag(tag);

        List<Map<String, Object>> filters = new ArrayList<>();
        filters.add(mapOf("term", mapOf("visible", true)));
        if (normalizedTag != null) {
            filters.add(mapOf("term", mapOf("tags", normalizedTag)));
        }
        if (sectionId != null) {
            filters.add(mapOf("term", mapOf("sectionId", sectionId)));
        }

        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("filter", filters);
        if (q != null && !q.isBlank()) {
            bool.put("must", mapOf("multi_match", mapOf(
                    "query", q.trim(),
                    "fields", List.of("title^3", "content"))));
        } else {
            bool.put("must", mapOf("match_all", Collections.emptyMap()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from);
        body.put("size", size);
        body.put("track_total_hits", true);
        body.put("query", mapOf("bool", bool));
        body.put("sort", List.of(
                mapOf("createdAt", mapOf("order", "desc")),
                mapOf("id", mapOf("order", "desc"))));
        body.put("_source", List.of("id", "version"));

        HttpResponse<String> response = send("POST", "/" + index + "/_search", json(body));
        requireSuccess(response, "search");
        return parseSearchPage(response.body());
    }

    @Override
    public Optional<ThreadSearchDocument> read(String index, long id) {
        validateIndex(index);
        validateId(id);
        HttpResponse<String> response = send("GET", "/" + index + "/_doc/" + id, null);
        if (response.statusCode() == 404) {
            if (isMissingDocument(response.body())) {
                return Optional.empty();
            }
            throw failure("read document returned an index or protocol error");
        }
        requireSuccess(response, "read document");
        JsonNode root = parseJson(response.body(), "read document");
        JsonNode source = root.get("_source");
        if (source == null || !source.isObject()) {
            throw failure("read document returned no source");
        }
        return Optional.of(parseDocument(source));
    }

    @Override
    public long count(String index) {
        validateIndex(index);
        Map<String, Object> body = mapOf("query", mapOf("match_all", Collections.emptyMap()));
        HttpResponse<String> response = send("GET", "/" + index + "/_count", json(body));
        requireSuccess(response, "count documents");
        JsonNode root = parseJson(response.body(), "count documents");
        JsonNode count = root.get("count");
        if (count == null || !count.canConvertToLong() || count.asLong() < 0) {
            throw failure("count response is invalid");
        }
        return count.asLong();
    }

    @Override
    public void refresh(String index) {
        validateIndex(index);
        HttpResponse<String> response = send("POST", "/" + index + "/_refresh", null);
        requireSuccess(response, "refresh index");
    }

    @Override
    public Optional<String> activeIndex() {
        HttpResponse<String> response = send("GET", "/_alias/" + ContentSearchIndex.ALIAS, null);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        requireSuccess(response, "read active alias");
        JsonNode root = parseJson(response.body(), "read active alias");
        if (!root.isObject()) {
            throw failure("active alias response is invalid");
        }
        List<String> indices = new ArrayList<>();
        root.fieldNames().forEachRemaining(indices::add);
        if (indices.isEmpty()) {
            return Optional.empty();
        }
        if (indices.size() != 1) {
            throw failure("active alias points to multiple indices");
        }
        validatePhysicalIndex(indices.get(0));
        return Optional.of(indices.get(0));
    }

    @Override
    public void switchAlias(String newIndex) {
        validatePhysicalIndex(newIndex);
        HttpResponse<String> head = send("HEAD", "/" + newIndex, null);
        requireSuccess(head, "verify new index");
        Optional<String> oldIndex = activeIndex();

        List<Map<String, Object>> actions = new ArrayList<>();
        if (oldIndex.isPresent()) {
            actions.add(mapOf("remove", mapOf(
                    "index", oldIndex.get(),
                    "alias", ContentSearchIndex.ALIAS)));
        }
        actions.add(mapOf("add", mapOf(
                "index", newIndex,
                "alias", ContentSearchIndex.ALIAS,
                "is_write_index", true)));
        HttpResponse<String> response = send(
                "POST",
                "/_aliases",
                json(mapOf("actions", actions)));
        requireSuccess(response, "switch active alias");
        JsonNode acknowledgement = parseJson(response.body(), "switch active alias").get("acknowledged");
        if (acknowledgement == null || !acknowledgement.isBoolean() || !acknowledgement.asBoolean()) {
            throw failure("alias switch was not acknowledged");
        }
    }

    private SearchPage parseSearchPage(String body) {
        JsonNode root = parseJson(body, "search");
        JsonNode hitsRoot = root.get("hits");
        if (hitsRoot == null || !hitsRoot.isObject()) {
            throw failure("search response has no hits object");
        }
        JsonNode totalNode = hitsRoot.get("total");
        long total = parseTotal(totalNode);
        JsonNode hitsNode = hitsRoot.get("hits");
        if (hitsNode == null || !hitsNode.isArray()) {
            throw failure("search response has no hits array");
        }
        List<SearchHit> hits = new ArrayList<>();
        for (JsonNode hit : hitsNode) {
            JsonNode source = hit.get("_source");
            if (source == null || !source.isObject()) {
                throw failure("search hit has no source");
            }
            JsonNode id = source.get("id");
            JsonNode version = source.get("version");
            if (id == null || version == null || !id.canConvertToLong() || !version.canConvertToLong()) {
                throw failure("search hit has invalid id or version");
            }
            if (id.asLong() <= 0 || version.asLong() < 0) {
                throw failure("search hit has invalid id or version");
            }
            hits.add(new SearchHit(id.asLong(), version.asLong()));
        }
        return new SearchPage(hits, total);
    }

    private long parseTotal(JsonNode totalNode) {
        JsonNode value = totalNode;
        if (totalNode != null && totalNode.isObject()) {
            value = totalNode.get("value");
        }
        if (value == null || !value.canConvertToLong() || value.asLong() < 0) {
            throw failure("search total is invalid");
        }
        return value.asLong();
    }

    private ThreadSearchDocument parseDocument(JsonNode source) {
        long id = requiredLong(source, "id");
        validateId(id);
        long version = requiredLong(source, "version");
        if (version < 0) {
            throw failure("document version is invalid");
        }
        boolean visible = requiredBoolean(source, "visible");
        String title = requiredString(source, "title");
        String content = requiredString(source, "content");
        JsonNode tagsNode = source.get("tags");
        if (tagsNode == null || !tagsNode.isArray()) {
            throw failure("document tags are invalid");
        }
        List<String> tags = new ArrayList<>();
        for (JsonNode tag : tagsNode) {
            if (!tag.isTextual()) {
                throw failure("document tag is invalid");
            }
            tags.add(tag.textValue());
        }
        JsonNode sectionNode = source.get("sectionId");
        Long sectionId = sectionNode == null || sectionNode.isNull()
                ? null
                : requiredLong(source, "sectionId");
        if (sectionId != null && sectionId <= 0) {
            throw failure("document sectionId is invalid");
        }
        String createdAtValue = requiredString(source, "createdAt");
        Instant createdAt;
        try {
            createdAt = Instant.parse(createdAtValue);
        } catch (RuntimeException ex) {
            throw failure("document createdAt is invalid");
        }
        String eventId = requiredString(source, "eventId");
        String eventType = requiredString(source, "eventType");
        ThreadSearchDocument document = new ThreadSearchDocument(
                id,
                version,
                visible,
                title,
                content,
                List.copyOf(tags),
                sectionId,
                createdAt,
                eventId,
                eventType);
        validateDocument(document);
        return document;
    }

    private long requiredLong(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull() || !value.canConvertToLong()) {
            throw failure("document field " + field + " is invalid");
        }
        return value.asLong();
    }

    private boolean requiredBoolean(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isBoolean()) {
            throw failure("document field " + field + " is invalid");
        }
        return value.booleanValue();
    }

    private String requiredString(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            throw failure("document field " + field + " is invalid");
        }
        return value.textValue();
    }

    private boolean isMissingDocument(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode found = root.get("found");
            if (found != null && found.isBoolean() && !found.booleanValue()) {
                return true;
            }
            JsonNode result = root.get("result");
            if (result != null && "not_found".equals(result.asText())) {
                return true;
            }
            JsonNode error = root.get("error");
            JsonNode type = error == null ? null : error.get("type");
            return type != null && "document_missing_exception".equals(type.asText());
        } catch (JsonProcessingException ex) {
            return false;
        }
    }

    private boolean isVersionConflict(HttpResponse<String> response) {
        return response.statusCode() == 400
                && response.body() != null
                && response.body().contains("VERSION_CONFLICT");
    }

    private void validateDocument(ThreadSearchDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        validateId(document.id());
        if (document.version() < 0) {
            throw new IllegalArgumentException("version must be non-negative");
        }
        if (document.title() == null || document.content() == null || document.tags() == null) {
            throw new IllegalArgumentException("document text and tags must not be null");
        }
        if (document.tags().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("document tags must not contain null");
        }
        if (document.createdAt() == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
        if (document.eventId() == null || document.eventId().isBlank()
                || document.eventType() == null || document.eventType().isBlank()) {
            throw new IllegalArgumentException("event identity must not be blank");
        }
        if (!document.visible()
                && (!document.title().isEmpty()
                || !document.content().isEmpty()
                || !document.tags().isEmpty())) {
            throw new IllegalArgumentException("tombstone must have empty title, content and tags");
        }
    }

    private Map<String, Object> toDocumentMap(ThreadSearchDocument document) {
        List<String> normalizedTags = document.tags().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .map(tag -> tag.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", document.id());
        body.put("version", document.version());
        body.put("visible", document.visible());
        body.put("title", document.title());
        body.put("content", document.content());
        body.put("tags", normalizedTags);
        body.put("sectionId", document.sectionId());
        body.put("createdAt", document.createdAt().toString());
        body.put("eventId", document.eventId());
        body.put("eventType", document.eventType());
        return body;
    }

    private int validatePage(int page, int size) {
        if (page < 1) {
            throw new IllegalArgumentException("page must be positive");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
        }
        long from = ((long) page - 1L) * size;
        if (from >= MAX_FROM) {
            throw new IllegalArgumentException("search offset is too large");
        }
        return Math.toIntExact(from);
    }

    private String normalizeTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        String normalized = tag.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private void validateIndex(String index) {
        if (index == null || (!ContentSearchIndex.ALIAS.equals(index)
                && !MANAGED_INDEX.matcher(index).matches())) {
            throw new IllegalArgumentException("index is not a managed physical index or the managed alias");
        }
    }

    private void validatePhysicalIndex(String index) {
        if (index == null || !MANAGED_INDEX.matcher(index).matches()) {
            throw new IllegalArgumentException("index is not a managed physical index");
        }
    }

    private void validateId(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
    }

    private HttpResponse<String> send(String method, String path, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .timeout(readTimeout)
                .header("Accept", "application/json");
        if (!apiKey.isEmpty()) {
            builder.header("Authorization", "ApiKey " + apiKey);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw failure("request interrupted");
        } catch (IOException | RuntimeException ex) {
            throw failure("request failed", ex);
        }
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private void requireSuccess(HttpResponse<String> response, String operation) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw failure(operation + " returned HTTP " + response.statusCode());
        }
    }

    private String json(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw failure("request body could not be serialized", ex);
        }
    }

    private JsonNode parseJson(String body, String operation) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null) {
                throw failure(operation + " response is empty");
            }
            return root;
        } catch (JsonProcessingException ex) {
            throw failure(operation + " response is invalid", ex);
        }
    }

    private static Map<String, Object> mapOf(Object... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("map values must be paired");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put((String) values[i], values[i + 1]);
        }
        return result;
    }

    private static String normalizeBaseUrl(String url) {
        String candidate = url == null || url.isBlank() ? DEFAULT_URL : url.trim();
        URI parsed;
        try {
            parsed = URI.create(candidate);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Elasticsearch URL is invalid", ex);
        }
        if (!("http".equalsIgnoreCase(parsed.getScheme())
                || "https".equalsIgnoreCase(parsed.getScheme()))
                || parsed.getHost() == null || parsed.getUserInfo() != null
                || parsed.getRawQuery() != null || parsed.getFragment() != null) {
            throw new IllegalArgumentException("Elasticsearch URL must have an HTTP(S) host");
        }
        return candidate.replaceAll("/+$", "");
    }

    private static Duration requirePositiveDuration(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static IllegalStateException failure(String message) {
        return new IllegalStateException(message);
    }

    private static IllegalStateException failure(String message, Throwable cause) {
        return new IllegalStateException(message, cause);
    }
}
