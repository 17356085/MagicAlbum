package com.example.demo.search.adapter;

import com.example.demo.search.port.SearchVersionConflictException;
import com.example.demo.search.port.ContentSearchIndex;
import com.example.demo.search.port.ContentSearchIndex.SearchPage;
import com.example.demo.threads.dto.ThreadSearchDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ElasticsearchContentSearchIndexTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INDEX = "tongpin-threads-v20261019-a";

    @Test
    void createAndPutUseStrictMappingAndAtomicVersionScript() throws Exception {
        try (FakeElasticsearch server = new FakeElasticsearch(request -> new Response(200, "{}", 0))) {
            ElasticsearchContentSearchIndex index = adapter(server);
            index.create(INDEX);
            index.put(INDEX, document());

            Request create = server.requests().get(0);
            JsonNode createBody = MAPPER.readTree(create.body());
            assertThat(create.method()).isEqualTo("PUT");
            assertThat(create.path()).isEqualTo("/" + INDEX);
            assertThat(createBody.at("/settings/number_of_shards").asInt()).isEqualTo(1);
            assertThat(createBody.at("/settings/number_of_replicas").asInt()).isEqualTo(0);
            assertThat(createBody.at("/mappings/dynamic").asText()).isEqualTo("strict");
            assertThat(createBody.at("/mappings/properties/title/type").asText()).isEqualTo("text");
            assertThat(createBody.at("/mappings/properties/title/analyzer").asText()).isEqualTo("cjk");
            assertThat(createBody.at("/mappings/properties/content/analyzer").asText()).isEqualTo("cjk");
            assertThat(createBody.at("/mappings/properties/tags/type").asText()).isEqualTo("keyword");
            assertThat(createBody.at("/mappings/properties/createdAt/type").asText()).isEqualTo("date");

            Request put = server.requests().get(1);
            JsonNode putBody = MAPPER.readTree(put.body());
            assertThat(put.method()).isEqualTo("POST");
            assertThat(put.path()).isEqualTo("/" + INDEX + "/_update/74");
            assertThat(putBody.at("/scripted_upsert").asBoolean()).isTrue();
            assertThat(putBody.at("/upsert").isObject()).isTrue();
            assertThat(putBody.at("/upsert").size()).isZero();
            assertThat(putBody.at("/script/lang").asText()).isEqualTo("painless");
            assertThat(putBody.at("/script/source").asText())
                    .contains("params.doc.version > ctx._source.version")
                    .contains("params.doc.version == ctx._source.version")
                    .contains("VERSION_CONFLICT")
                    .contains("ctx.op = 'noop'");
            assertThat(putBody.at("/script/params/doc/tags/0").asText()).isEqualTo("java");
            assertThat(putBody.at("/script/params/doc/tags/1").asText()).isEqualTo("search");
            List<String> documentFields = new ArrayList<>();
            putBody.at("/script/params/doc").fieldNames().forEachRemaining(documentFields::add);
            assertThat(documentFields)
                    .containsExactlyInAnyOrder(
                            "id", "version", "visible", "title", "content", "tags",
                            "sectionId", "createdAt", "eventId", "eventType");
        }
    }

    @Test
    void searchBuildsVisibleExactFiltersAndReturnsOnlyIdsAndVersions() throws Exception {
        String response = "{\"hits\":{\"total\":{\"value\":2,\"relation\":\"eq\"},"
                + "\"hits\":[{\"_source\":{\"id\":74,\"version\":3}},"
                + "{\"_source\":{\"id\":72,\"version\":2}}]}}";
        try (FakeElasticsearch server = new FakeElasticsearch(request -> new Response(200, response, 0))) {
            ElasticsearchContentSearchIndex index = adapter(server);
            SearchPage page = index.search(ContentSearchIndex.ALIAS, "blue album", " Mixed Tag ", 7L, 2, 10);

            assertThat(page.total()).isEqualTo(2);
            assertThat(page.hits()).hasSize(2);
            assertThat(page.hits().get(0).id()).isEqualTo(74L);
            assertThat(page.hits().get(0).version()).isEqualTo(3L);
            assertThat(page.hits().get(1).id()).isEqualTo(72L);
            assertThat(page.hits().get(1).version()).isEqualTo(2L);

            Request request = server.requests().get(0);
            JsonNode body = MAPPER.readTree(request.body());
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/" + ContentSearchIndex.ALIAS + "/_search");
            assertThat(body.at("/from").asInt()).isEqualTo(10);
            assertThat(body.at("/size").asInt()).isEqualTo(10);
            assertThat(body.at("/track_total_hits").asBoolean()).isTrue();
            assertThat(body.at("/query/bool/filter").toString())
                    .contains("visible")
                    .contains("true")
                    .contains("mixed tag")
                    .contains("sectionId")
                    .contains("7");
            assertThat(body.at("/query/bool/must/multi_match/query").asText()).isEqualTo("blue album");
            assertThat(body.at("/query/bool/must/multi_match/fields/0").asText()).isEqualTo("title^3");
            assertThat(body.at("/query/bool/must/multi_match/fields/1").asText()).isEqualTo("content");
            assertThat(body.at("/_source/0").asText()).isEqualTo("id");
            assertThat(body.at("/_source/1").asText()).isEqualTo("version");
        }
    }

    @Test
    void switchAliasVerifiesIndexAndUsesOneAtomicRemoveAddRequest() throws Exception {
        String oldIndex = "tongpin-threads-v20261018-old";
        try (FakeElasticsearch server = new FakeElasticsearch(request -> {
            if ("GET".equals(request.method()) && request.path().equals("/_alias/tongpin-threads")) {
                return new Response(200, "{\"" + oldIndex
                        + "\":{\"aliases\":{\"tongpin-threads\":{}}}}", 0);
            }
            if (request.path().equals("/_aliases")) {
                return new Response(200, "{\"acknowledged\":true}", 0);
            }
            return new Response(200, "{}", 0);
        })) {
            ElasticsearchContentSearchIndex index = adapter(server);
            index.switchAlias(INDEX);

            assertThat(server.requests()).hasSize(3);
            assertThat(server.requests().get(0).method()).isEqualTo("HEAD");
            assertThat(server.requests().get(1).method()).isEqualTo("GET");
            assertThat(server.requests().get(2).method()).isEqualTo("POST");
            Request aliasRequest = server.requests().get(2);
            JsonNode actions = MAPPER.readTree(aliasRequest.body()).get("actions");
            assertThat(actions).hasSize(2);
            assertThat(actions.get(0).at("/remove/index").asText()).isEqualTo(oldIndex);
            assertThat(actions.get(0).at("/remove/alias").asText()).isEqualTo("tongpin-threads");
            assertThat(actions.get(1).at("/add/index").asText()).isEqualTo(INDEX);
            assertThat(actions.get(1).at("/add/alias").asText()).isEqualTo("tongpin-threads");
            assertThat(actions.get(1).at("/add/is_write_index").asBoolean()).isTrue();
        }
    }

    @Test
    void an_unacknowledged_alias_response_is_not_success() throws Exception {
        try (FakeElasticsearch server = new FakeElasticsearch(request -> {
            if (request.path().equals("/_alias/tongpin-threads")) {
                return new Response(404, "{}", 0);
            }
            return new Response(200, "{\"acknowledged\":false}", 0);
        })) {
            assertThatThrownBy(() -> adapter(server).switchAlias(INDEX))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("not acknowledged");
        }
    }

    @Test
    void readDistinguishesMissingDocumentFromMissingIndex() throws Exception {
        try (FakeElasticsearch server = new FakeElasticsearch(request ->
                new Response(404, "{\"_index\":\"" + INDEX + "\",\"_id\":\"74\",\"found\":false}", 0))) {
            ElasticsearchContentSearchIndex index = adapter(server);
            assertThat(index.read(INDEX, 74)).isEmpty();
        }

        try (FakeElasticsearch server = new FakeElasticsearch(request ->
                new Response(404, "{\"error\":{\"type\":\"index_not_found_exception\"}}", 0))) {
            ElasticsearchContentSearchIndex index = adapter(server);
            assertThatThrownBy(() -> index.read(INDEX, 74))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("index or protocol error");
        }
    }

    @Test
    void invalidNamesIdsPagesAndSizesAreRejectedBeforeHttp() throws Exception {
        try (FakeElasticsearch server = new FakeElasticsearch(request -> new Response(200, "{}", 0))) {
            ElasticsearchContentSearchIndex index = adapter(server);
            assertThatThrownBy(() -> index.create("tongpin-threads")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> index.create("tongpin-threads-vBAD")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> index.search("other-index", null, null, null, 1, 10))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> index.read(INDEX, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> index.search(INDEX, null, null, null, 0, 10))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> index.search(INDEX, null, null, null, 1, 11))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> index.search(INDEX, null, null, null, 1001, 10))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(server.requests()).isEmpty();
        }
    }

    @Test
    void conflictAndTimeoutRemainBoundedAndSurfaceAsFailures() throws Exception {
        try (FakeElasticsearch server = new FakeElasticsearch(request ->
                new Response(400, "{\"error\":{\"reason\":\"VERSION_CONFLICT\"}}", 0))) {
            ElasticsearchContentSearchIndex index = adapter(server);
            assertThatThrownBy(() -> index.put(INDEX, document()))
                    .isInstanceOf(SearchVersionConflictException.class)
                    .hasMessage("VERSION_CONFLICT");
        }

        try (FakeElasticsearch server = new FakeElasticsearch(request ->
                new Response(200, "{\"count\":0}", 600))) {
            ElasticsearchContentSearchIndex index = new ElasticsearchContentSearchIndex(
                    MAPPER,
                    server.url(),
                    Duration.ofMillis(100),
                    Duration.ofMillis(100),
                    1);
            long start = System.nanoTime();
            assertThatThrownBy(() -> index.count(INDEX)).isInstanceOf(IllegalStateException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        }
    }

    private static ElasticsearchContentSearchIndex adapter(FakeElasticsearch server) {
        return new ElasticsearchContentSearchIndex(
                MAPPER,
                server.url(),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                0);
    }

    private static ThreadSearchDocument document() {
        return new ThreadSearchDocument(
                74L,
                3L,
                true,
                "Blue Album",
                "Searchable thread body",
                List.of("JAVA", "Search"),
                7L,
                Instant.parse("2026-10-19T08:00:00Z"),
                "event-3",
                "THREAD_UPSERTED");
    }

    private record Request(String method, String path, String body) {
    }

    private record Response(int status, String body, long delayMillis) {
    }

    private static final class FakeElasticsearch implements AutoCloseable {
        private final HttpServer server;
        private final CopyOnWriteArrayList<Request> requests = new CopyOnWriteArrayList<>();
        private final Function<Request, Response> responder;

        private FakeElasticsearch(Function<Request, Response> responder) throws IOException {
            this.responder = responder;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body);
            requests.add(request);
            Response response = responder.apply(request);
            if (response.delayMillis() > 0) {
                try {
                    Thread.sleep(response.delayMillis());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] responseBody = response.body().getBytes(StandardCharsets.UTF_8);
            if ("HEAD".equals(exchange.getRequestMethod()) || responseBody.length == 0) {
                exchange.sendResponseHeaders(response.status(), -1);
            } else {
                exchange.sendResponseHeaders(response.status(), responseBody.length);
                exchange.getResponseBody().write(responseBody);
            }
            exchange.close();
        }

        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private List<Request> requests() {
            return new ArrayList<>(requests);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
