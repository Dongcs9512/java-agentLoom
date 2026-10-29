package com.aia.llm.openai;

import com.aia.llm.ChatMessage;
import com.aia.llm.ChatMessageRole;
import com.aia.llm.ChatModelConfig;
import com.aia.llm.ChatRequest;
import com.aia.llm.ChatResponse;
import com.aia.llm.ToolCall;
import com.aia.llm.ToolDefinition;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleChatModelTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private OpenAiCompatibleChatModel model(String responseBody) {
        return model(responseBody, 200);
    }

    private OpenAiCompatibleChatModel model(String responseBody, int status) {
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        return new OpenAiCompatibleChatModel(baseUrl, "test-key", "test-model", HttpClient.newHttpClient());
    }

    private ChatRequest request() {
        return new ChatRequest(
                List.of(ChatMessage.system("sys"), ChatMessage.user("hi")),
                List.of(),
                new ChatModelConfig(null, null));
    }

    @Test
    void parsesTextResponse() {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"你好，有什么可以帮你？\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":8}}";
        ChatResponse resp = model(body).chat(request());
        assertEquals("你好，有什么可以帮你？", resp.message().content());
        assertEquals("stop", resp.finishReason());
        assertEquals(ChatMessageRole.ASSISTANT, resp.message().role());
    }

    @Test
    void parsesToolCallResponse() {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"query_order\",\"arguments\":\"{\\\"orderId\\\":\\\"123\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":10}}";
        ChatResponse resp = model(body).chat(request());
        List<ToolCall> calls = resp.message().toolCalls();
        assertEquals(1, calls.size());
        assertEquals("call_1", calls.get(0).id());
        assertEquals("query_order", calls.get(0).name());
        assertEquals(Map.of("orderId", "123"), calls.get(0).arguments());
    }

    @Test
    void sendsCorrectRequest() {
        AtomicReference<String> captured = new AtomicReference<>();
        AtomicReference<String> authHeader = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        ToolDefinition tool = new ToolDefinition("query_order", "desc", Map.of("orderId", Map.of("type", "string")));
        ChatRequest req = new ChatRequest(
                List.of(ChatMessage.system("sys"), ChatMessage.user("hi")),
                List.of(tool),
                new ChatModelConfig(0.5, 100));

        new OpenAiCompatibleChatModel(baseUrl, "test-key", "test-model", HttpClient.newHttpClient()).chat(req);

        assertEquals("Bearer test-key", authHeader.get());
        String body = captured.get();
        assertTrue(body.contains("\"model\":\"test-model\""), body);
        assertTrue(body.contains("\"role\":\"system\""), body);
        assertTrue(body.contains("\"query_order\""), body);
        assertTrue(body.contains("\"type\":\"string\""), body);
    }

    @Test
    void throwsOnNon200() {
        ChatModelException ex = assertThrows(ChatModelException.class,
                () -> model("{\"error\":{\"message\":\"boom\"}}", 500).chat(request()));
        assertEquals(500, ex.statusCode());
    }
}
