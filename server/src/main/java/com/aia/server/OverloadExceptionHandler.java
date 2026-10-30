package com.aia.server;

import com.aia.core.OverloadedException;
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

    @ExceptionHandler(OverloadedException.class)
    public ResponseEntity<Map<String, Object>> handleOverloaded(OverloadedException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(Map.of(
                        "error", "overloaded",
                        "message", e.getMessage()));
    }
}
