package io.github.dongcs9512.agentloom.rag.openai;

import io.github.dongcs9512.agentloom.rag.EmbeddingModel;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleEmbeddingModelTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authHeader = new AtomicReference<>();

    private int status = 200;
    private String responseBody = "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
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

    private OpenAiCompatibleEmbeddingModel model(int dimension) {
        return new OpenAiCompatibleEmbeddingModel(baseUrl, "test-key", "text-embedding-v3", dimension,
                HttpClient.newHttpClient());
    }

    @Test
    void embedSendsRequestAndParsesVector() {
        float[] vector = model(3).embed("你好");

        assertEquals(3, vector.length);
        assertEquals(0.1f, vector[0], 0.0001);
        assertEquals(0.3f, vector[2], 0.0001);

        assertEquals("Bearer test-key", authHeader.get());
        String body = requestBody.get();
        assertTrue(body.contains("\"model\":\"text-embedding-v3\""), body);
        assertTrue(body.contains("\"input\":\"你好\""), body);
    }

    @Test
    void embedBatchSendsArrayInputAndReturnsVectorsInOrder() {
        responseBody = "{\"data\":["
                + "{\"index\":0,\"embedding\":[1.0,0.0,0.0]},"
                + "{\"index\":1,\"embedding\":[0.0,1.0,0.0]}]}";

        List<float[]> vectors = model(3).embedBatch(List.of("甲", "乙"));

        assertEquals(2, vectors.size());
        assertEquals(1.0f, vectors.get(0)[0], 0.0001);
        assertEquals(1.0f, vectors.get(1)[1], 0.0001);
        assertTrue(requestBody.get().contains("\"input\":[\"甲\",\"乙\"]"), requestBody.get());
    }

    @Test
    void embedBatchWithSingleTextSendsStringInput() {
        model(3).embedBatch(List.of("唯一"));

        assertTrue(requestBody.get().contains("\"input\":\"唯一\""), requestBody.get());
    }

    @Test
    void throwsOnNon200() {
        status = 401;
        responseBody = "{\"error\":{\"message\":\"bad key\"}}";

        EmbeddingException ex = assertThrows(EmbeddingException.class, () -> model(3).embed("x"));
        assertEquals(401, ex.statusCode());
    }

    @Test
    void throwsWhenReturnedDimensionDiffersFromConfigured() {
        responseBody = "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}";

        assertThrows(EmbeddingException.class, () -> model(3).embed("x"));
    }

    @Test
    void defaultEmbedBatchWorksForSingleTextEmbedder() {
        EmbeddingModel single = new EmbeddingModel() {
            @Override
            public float[] embed(String text) {
                return new float[]{text.length()};
            }

            @Override
            public int dimension() {
                return 1;
            }
        };

        List<float[]> vectors = single.embedBatch(List.of("a", "bbb"));

        assertEquals(2, vectors.size());
        assertEquals(1f, vectors.get(0)[0], 0.0001);
        assertEquals(3f, vectors.get(1)[0], 0.0001);
    }
}
