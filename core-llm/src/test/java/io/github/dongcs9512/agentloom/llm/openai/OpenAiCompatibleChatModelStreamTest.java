package io.github.dongcs9512.agentloom.llm.openai;

import io.github.dongcs9512.agentloom.llm.ChatMessage;
import io.github.dongcs9512.agentloom.llm.ChatModelConfig;
import io.github.dongcs9512.agentloom.llm.ChatRequest;
import io.github.dongcs9512.agentloom.llm.ToolCall;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleChatModelStreamTest {

    private HttpServer server;
    private OpenAiCompatibleChatModel model;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
        model = new OpenAiCompatibleChatModel(baseUrl, "k", "m", HttpClient.newHttpClient());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void respond(String sseBody) {
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            byte[] bytes = sseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    private ChatRequest request() {
        return new ChatRequest(List.of(ChatMessage.user("hi")), List.of(), new ChatModelConfig(null, null));
    }

    @Test
    void streamsTextChunks() {
        respond("data: {\"choices\":[{\"delta\":{\"content\":\"你\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n");

        StringBuilder sb = new StringBuilder();
        List<String> finishReasons = new ArrayList<>();
        model.stream(request(), chunk -> {
            if (chunk.content() != null) {
                sb.append(chunk.content());
            }
            if (chunk.finishReason() != null) {
                finishReasons.add(chunk.finishReason());
            }
        });

        assertEquals("你好", sb.toString());
        assertTrue(finishReasons.contains("stop"));
    }

    @Test
    void streamsAndAccumulatesToolCalls() {
        respond("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"query_order\",\"arguments\":\"{\\\"orderId\\\":\\\"\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"123\\\"}\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                + "data: [DONE]\n");

        List<ToolCall> toolCalls = new ArrayList<>();
        model.stream(request(), chunk -> toolCalls.addAll(chunk.toolCalls()));

        assertEquals(1, toolCalls.size());
        assertEquals("call_1", toolCalls.get(0).id());
        assertEquals("query_order", toolCalls.get(0).name());
        assertEquals(Map.of("orderId", "123"), toolCalls.get(0).arguments());
    }
}
