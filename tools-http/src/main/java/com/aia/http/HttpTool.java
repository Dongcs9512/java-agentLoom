package com.aia.http;

import com.aia.core.Tool;
import com.aia.core.ToolInvocation;
import com.aia.core.ToolResult;
import com.jayway.jsonpath.JsonPath;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public final class HttpTool implements Tool {

    private final HttpToolConfig config;
    private final HttpClient httpClient;

    public HttpTool(HttpToolConfig config) {
        this(config, HttpClient.newHttpClient());
    }

    public HttpTool(HttpToolConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    @Override
    public String name() {
        return config.name();
    }

    @Override
    public String description() {
        return config.description();
    }

    @Override
    public Map<String, Object> parameters() {
        return config.parameters();
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        String body = fillTemplate(config.bodyTemplate(), invocation.arguments());
        Map<String, Object> context = new HashMap<>();
        context.put("method", config.method());
        context.put("url", config.url());
        context.put("body", body);

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(config.url()))
                    .timeout(Duration.ofMillis(config.timeoutMs()))
                    .header("Content-Type", "application/json");
            if ("GET".equalsIgnoreCase(config.method())) {
                builder.GET();
            } else {
                builder.method(config.method(), HttpRequest.BodyPublishers.ofString(body));
            }
            if (config.headers() != null) {
                config.headers().forEach(builder::header);
            }
            if (config.authenticator() != null) {
                config.authenticator().applyHeaders(context).forEach(builder::header);
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return ToolResult.error("HTTP " + response.statusCode() + ": " + response.body());
            }
            return ToolResult.ok(extract(response.body()));
        } catch (Exception e) {
            return ToolResult.error("HTTP 工具执行失败: " + e.getMessage());
        }
    }

    private String fillTemplate(String template, Map<String, Object> args) {
        if (template == null) {
            return "";
        }
        String result = template;
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
        }
        return result.replaceAll("\\{\\{[^}]+}}", "");
    }

    private String extract(String body) {
        if (config.extract() == null || config.extract().isEmpty()) {
            return body;
        }
        try {
            Object value = JsonPath.read(body, config.extract());
            return value == null ? "" : String.valueOf(value);
        } catch (Exception e) {
            return body;
        }
    }
}
