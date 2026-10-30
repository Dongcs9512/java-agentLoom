package com.aia.rag.openai;

import com.aia.rag.RerankModel;
import com.aia.rag.RerankResult;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleRerankModelTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authHeader = new AtomicReference<>();

    private int status = 200;
    private String responseBody = "{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.2}]}";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/rerank", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private RerankModel model() {
        return new OpenAiCompatibleRerankModel(baseUrl, "test-key", "BAAI/bge-reranker-v2-m3",
                HttpClient.newHttpClient());
    }

    @Test
    void sendsQueryDocumentsAndTopN() {
        model().rerank("退款", List.of("文档A", "文档B"), 2);

        String body = requestBody.get();
        assertTrue(body.contains("\"model\":\"BAAI/bge-reranker-v2-m3\""), body);
        assertTrue(body.contains("\"query\":\"退款\""), body);
        assertTrue(body.contains("\"documents\":[\"文档A\",\"文档B\"]"), body);
        assertTrue(body.contains("\"top_n\":2"), body);
        assertEquals("Bearer test-key", authHeader.get());
    }

    @Test
    void parsesResultsOrderedByRelevance() {
        List<RerankResult> results = model().rerank("退款", List.of("文档A", "文档B"), 2);

        assertEquals(2, results.size());
        assertEquals(1, results.get(0).index());
        assertEquals(0.9, results.get(0).score(), 0.0001);
        assertEquals(0, results.get(1).index());
    }

    @Test
    void emptyDocumentsShortCircuits() {
        List<RerankResult> results = model().rerank("退款", List.of(), 3);

        assertTrue(results.isEmpty());
        assertEquals(null, requestBody.get(), "不应发起 HTTP 请求");
    }

    @Test
    void throwsOnNon200() {
        status = 400;
        responseBody = "{\"message\":\"bad request\"}";

        RerankException ex = assertThrows(RerankException.class, () -> model().rerank("q", List.of("a"), 1));
        assertEquals(400, ex.statusCode());
    }
}
