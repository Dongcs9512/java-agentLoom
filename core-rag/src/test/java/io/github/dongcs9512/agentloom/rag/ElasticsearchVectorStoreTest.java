package io.github.dongcs9512.agentloom.rag;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElasticsearchVectorStoreTest {

    private HttpServer server;
    private String baseUrl;

    private int headStatus = 200;
    private int bulkStatus = 200;
    private int searchStatus = 200;
    private String bulkResponse = "{\"took\":1,\"errors\":false,\"items\":[]}";
    private String searchResponse = "{\"hits\":{\"hits\":[]}}";
    private final AtomicReference<String> bulkBody = new AtomicReference<>();
    private final AtomicReference<String> bulkUri = new AtomicReference<>();
    private final AtomicReference<String> searchBody = new AtomicReference<>();
    private final AtomicReference<String> putBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ElasticsearchVectorStore store(int dimension) {
        server.createContext("/", this::route);
        return new ElasticsearchVectorStore(baseUrl, "test-index", dimension, HttpClient.newHttpClient());
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if (method.equals("HEAD")) {
            exchange.sendResponseHeaders(headStatus, -1);
            exchange.close();
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (method.equals("PUT")) {
            putBody.set(body);
            respond(exchange, 200, "{\"acknowledged\":true}");
        } else if (path.endsWith("/_bulk")) {
            bulkBody.set(body);
            bulkUri.set(exchange.getRequestURI().toString());
            respond(exchange, bulkStatus, bulkResponse);
        } else if (path.endsWith("/_search")) {
            searchBody.set(body);
            respond(exchange, searchStatus, searchResponse);
        } else {
            respond(exchange, 404, "{}");
        }
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void upsertSendsBulkWithIndexActionsAndSource() {
        ElasticsearchVectorStore s = store(2);
        s.upsert(List.of(new VectorRecord("a", "hello", new float[]{1f, 0f}, Map.of("docId", "d1"))));

        String body = bulkBody.get();
        assertNotNull(body);
        assertTrue(body.contains("\"index\":{\"_index\":\"test-index\",\"_id\":\"a\"}"), body);
        assertTrue(body.contains("\"text\":\"hello\""), body);
        assertTrue(body.contains("\"vector\":[1.0,0.0]"), body);
        assertTrue(body.contains("\"metadata\":{\"docId\":\"d1\"}"), body);
    }

    @Test
    void searchSendsKnnQueryAndParsesHits() {
        searchResponse = "{\"hits\":{\"hits\":["
                + "{\"_id\":\"a\",\"_score\":0.99,\"_source\":{\"text\":\"hello\",\"metadata\":{\"docId\":\"d1\"}}},"
                + "{\"_id\":\"b\",\"_score\":0.5,\"_source\":{\"text\":\"world\",\"metadata\":{\"docId\":\"d1\"}}}"
                + "]}}";
        ElasticsearchVectorStore s = store(2);

        List<SearchHit> hits = s.search(new VectorQuery(new float[]{1f, 0f}, 2, Map.of()));

        assertEquals(2, hits.size());
        assertEquals("a", hits.get(0).id());
        assertEquals("hello", hits.get(0).text());
        assertEquals(0.99f, hits.get(0).score(), 0.001);
        assertEquals("d1", hits.get(0).metadata().get("docId"));

        String body = searchBody.get();
        assertTrue(body.contains("\"knn\""), body);
        assertTrue(body.contains("\"query_vector\":[1.0,0.0]"), body);
        assertTrue(body.contains("\"k\":2"), body);
    }

    @Test
    void searchWithFilterAddsTermQueries() {
        ElasticsearchVectorStore s = store(2);
        s.search(new VectorQuery(new float[]{1f, 0f}, 2, Map.of("docId", "d1")));

        String body = searchBody.get();
        assertTrue(body.contains("\"filter\":{\"bool\":{\"must\":[{\"term\":{\"metadata.docId\":\"d1\"}}]}}"), body);
    }

    @Test
    void bulkRequestsWaitForRefresh() {
        ElasticsearchVectorStore s = store(2);
        s.upsert(List.of(new VectorRecord("a", "a", new float[]{1f, 0f}, Map.of())));

        assertNotNull(bulkUri.get());
        assertTrue(bulkUri.get().contains("refresh=wait_for"), bulkUri.get());
    }

    @Test
    void deleteSendsBulkDeleteActions() {
        ElasticsearchVectorStore s = store(2);
        s.delete(List.of("a", "b"));

        String body = bulkBody.get();
        assertNotNull(body);
        assertTrue(body.contains("\"delete\":{\"_index\":\"test-index\",\"_id\":\"a\"}"), body);
        assertTrue(body.contains("\"delete\":{\"_index\":\"test-index\",\"_id\":\"b\"}"), body);
    }

    @Test
    void capabilitiesReportsMetadataFilterAndBatchUpsert() {
        ElasticsearchVectorStore s = store(2);
        assertTrue(s.capabilities().contains(VectorStoreCapability.METADATA_FILTER));
        assertTrue(s.capabilities().contains(VectorStoreCapability.BATCH_UPSERT));
    }

    @Test
    void upsertRejectsWrongDimension() {
        ElasticsearchVectorStore s = store(3);
        assertThrows(IllegalArgumentException.class,
                () -> s.upsert(List.of(new VectorRecord("a", "a", new float[]{1f, 0f}, Map.of()))));
    }

    @Test
    void createsIndexWhenMissing() {
        headStatus = 404;
        ElasticsearchVectorStore s = store(3);
        s.upsert(List.of(new VectorRecord("a", "a", new float[]{1f, 0f, 0f}, Map.of())));

        String body = putBody.get();
        assertNotNull(body);
        assertTrue(body.contains("\"dense_vector\""), body);
        assertTrue(body.contains("\"dims\":3"), body);
        assertTrue(body.contains("\"flattened\""), body);
    }

    @Test
    void throwsOnNon200() {
        searchStatus = 500;
        searchResponse = "{\"error\":\"boom\"}";
        ElasticsearchVectorStore s = store(2);
        VectorStoreException ex = assertThrows(VectorStoreException.class,
                () -> s.search(new VectorQuery(new float[]{1f, 0f}, 2, Map.of())));
        assertEquals(500, ex.statusCode());
    }

    @Test
    void bulkWithErrorsThrows() {
        bulkResponse = "{\"errors\":true,\"items\":[{\"index\":{\"status\":400,\"error\":{\"type\":\"x\",\"reason\":\"bad dims\"}}}]}";
        ElasticsearchVectorStore s = store(2);
        assertThrows(VectorStoreException.class,
                () -> s.upsert(List.of(new VectorRecord("a", "a", new float[]{1f, 0f}, Map.of()))));
    }
}
