package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.core.OverloadedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OverloadExceptionHandlerTest {

    @Test
    void overloadMapsToServiceUnavailableWithRetryAfter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ResponseEntity<Map<String, Object>> response = new OverloadExceptionHandler(registry)
                .handleOverloaded(new OverloadedException("模型 并发已满，请稍后重试"));

        assertEquals(503, response.getStatusCode().value());
        assertEquals("1", response.getHeaders().getFirst("Retry-After"));
        assertEquals("overloaded", response.getBody().get("error"));
        assertEquals(1.0, registry.counter("agentloom.guard.rejected").count(), 0.0001);
    }
}
