package com.aia.server;

import com.aia.core.OverloadedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OverloadExceptionHandlerTest {

    @Test
    void overloadMapsToServiceUnavailableWithRetryAfter() {
        ResponseEntity<Map<String, Object>> response =
                new OverloadExceptionHandler().handleOverloaded(new OverloadedException("模型 并发已满，请稍后重试"));

        assertEquals(503, response.getStatusCode().value());
        assertEquals("1", response.getHeaders().getFirst("Retry-After"));
        assertEquals("overloaded", response.getBody().get("error"));
    }
}
