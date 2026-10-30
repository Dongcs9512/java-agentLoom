package io.github.dongcs9512.agentloom.http;

import java.util.Map;

public record HttpToolConfig(
        String name,
        String description,
        Map<String, Object> parameters,
        String method,
        String url,
        Map<String, String> headers,
        String bodyTemplate,
        String extract,
        int timeoutMs,
        Authenticator authenticator) {
}
