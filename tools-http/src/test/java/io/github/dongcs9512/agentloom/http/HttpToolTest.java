package io.github.dongcs9512.agentloom.http;

import io.github.dongcs9512.agentloom.core.ToolInvocation;
import io.github.dongcs9512.agentloom.core.ToolResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpToolTest {

    private HttpServer server;
    private String baseUrl;

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

    @Test
    void sendsRequestAndExtractsField() throws IOException {
        AtomicReference<String> captured = new AtomicReference<>();
        server.createContext("/api/query", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{\"code\":0,\"data\":\"订单已发货\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        HttpToolConfig cfg = new HttpToolConfig("query_order", "查订单",
                Map.of("orderId", Map.of("type", "string")), "POST", baseUrl + "/api/query",
                Map.of(), "{\"orderId\":\"{{orderId}}\"}", "$.data", 3000, null);
        ToolResult r = new HttpTool(cfg).execute(new ToolInvocation("c1", "query_order", Map.of("orderId", "123")));

        assertFalse(r.isError());
        assertEquals("订单已发货", r.content());
        assertTrue(captured.get().contains("\"123\""));
    }

    @Test
    void non2xxReturnsError() throws IOException {
        server.createContext("/api/query", exchange -> {
            byte[] bytes = "{\"error\":\"bad\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        HttpToolConfig cfg = new HttpToolConfig("query_order", "查订单", Map.of(),
                "POST", baseUrl + "/api/query", Map.of(), null, null, 3000, null);
        ToolResult r = new HttpTool(cfg).execute(new ToolInvocation("c1", "query_order", Map.of()));

        assertTrue(r.isError());
        assertTrue(r.content().contains("500"));
    }

    @Test
    void missingTemplateParamReplacedWithEmpty() throws IOException {
        AtomicReference<String> captured = new AtomicReference<>();
        server.createContext("/api/query", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{\"data\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        HttpToolConfig cfg = new HttpToolConfig("t", "d", Map.of(), "POST", baseUrl + "/api/query",
                Map.of(), "{\"a\":\"{{missing}}\"}", null, 3000, null);
        new HttpTool(cfg).execute(new ToolInvocation("c1", "t", Map.of()));

        assertTrue(captured.get().contains("\"a\":\"\""));
    }
}
