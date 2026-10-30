package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.core.OverloadedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 把过载统一映射成 503 + Retry-After，让调用方知道"稍后重试"而不是当成服务出错。
 */
@RestControllerAdvice
public class OverloadExceptionHandler {

    private final MeterRegistry meterRegistry;

    public OverloadExceptionHandler(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @ExceptionHandler(OverloadedException.class)
    public ResponseEntity<Map<String, Object>> handleOverloaded(OverloadedException e) {
        meterRegistry.counter("agentloom.guard.rejected").increment();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(Map.of(
                        "error", "overloaded",
                        "message", e.getMessage()));
    }
}
