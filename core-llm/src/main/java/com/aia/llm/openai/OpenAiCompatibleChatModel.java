package com.aia.llm.openai;

import com.aia.llm.ChatMessage;
import com.aia.llm.ChatMessageRole;
import com.aia.llm.ChatModel;
import com.aia.llm.ChatModelCapability;
import com.aia.llm.ChatModelConfig;
import com.aia.llm.ChatRequest;
import com.aia.llm.ChatResponse;
import com.aia.llm.ToolCall;
import com.aia.llm.ToolDefinition;
import com.aia.llm.Usage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class OpenAiCompatibleChatModel implements ChatModel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;

    public OpenAiCompatibleChatModel(String baseUrl, String apiKey, String model, HttpClient httpClient) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = httpClient;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        HttpRequest httpRequest = buildRequest(request);
        HttpResponse<String> httpResponse;
        try {
            httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new ChatModelException("请求模型失败: " + e.getMessage(), -1);
        }
        if (httpResponse.statusCode() != 200) {
            throw new ChatModelException("模型返回非 200 状态码: " + httpResponse.body(), httpResponse.statusCode());
        }
        return parse(httpResponse.body());
    }

    @Override
    public Set<ChatModelCapability> capabilities() {
        return Set.of(ChatModelCapability.TOOL_CALLING);
    }

    private HttpRequest buildRequest(ChatRequest request) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.set("messages", toMessages(request.messages()));
        if (!request.tools().isEmpty()) {
            body.set("tools", toTools(request.tools()));
        }
        if (request.config() != null) {
            ChatModelConfig config = request.config();
            if (config.temperature() != null) {
                body.put("temperature", config.temperature());
            }
            if (config.maxTokens() != null) {
                body.put("max_tokens", config.maxTokens());
            }
        }
        try {
            return HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();
        } catch (Exception e) {
            throw new ChatModelException("构建模型请求失败: " + e.getMessage(), -1);
        }
    }

    private ArrayNode toMessages(List<ChatMessage> messages) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (ChatMessage m : messages) {
            ObjectNode o = arr.addObject();
            o.put("role", m.role().name().toLowerCase(Locale.ROOT));
            if (m.content() != null) {
                o.put("content", m.content());
            }
            if (m.role() == ChatMessageRole.ASSISTANT && !m.toolCalls().isEmpty()) {
                ArrayNode tcs = o.putArray("tool_calls");
                for (ToolCall tc : m.toolCalls()) {
                    ObjectNode t = tcs.addObject();
                    t.put("id", tc.id());
                    t.put("type", "function");
                    ObjectNode fn = t.putObject("function");
                    fn.put("name", tc.name());
                    try {
                        fn.put("arguments", MAPPER.writeValueAsString(tc.arguments()));
                    } catch (Exception e) {
                        fn.put("arguments", "{}");
                    }
                }
            }
            if (m.role() == ChatMessageRole.TOOL) {
                o.put("tool_call_id", m.toolCallId());
            }
        }
        return arr;
    }

    private ArrayNode toTools(List<ToolDefinition> tools) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (ToolDefinition td : tools) {
            ObjectNode o = arr.addObject();
            o.put("type", "function");
            ObjectNode fn = o.putObject("function");
            fn.put("name", td.name());
            fn.put("description", td.description());
            fn.set("parameters", MAPPER.valueToTree(td.parameters()));
        }
        return arr;
    }

    private ChatResponse parse(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode choice = root.path("choices").path(0);
            JsonNode message = choice.path("message");

            String content = message.hasNonNull("content") ? message.get("content").asText() : null;

            List<ToolCall> toolCalls = new ArrayList<>();
            if (message.has("tool_calls") && !message.get("tool_calls").isNull()) {
                for (JsonNode tc : message.get("tool_calls")) {
                    JsonNode fn = tc.path("function");
                    Map<String, Object> args = MAPPER.readValue(fn.path("arguments").asText(), MAP_TYPE);
                    toolCalls.add(new ToolCall(tc.path("id").asText(), fn.path("name").asText(), args));
                }
            }

            ChatMessage msg = new ChatMessage(ChatMessageRole.ASSISTANT, content, toolCalls, null);
            String finishReason = choice.hasNonNull("finish_reason") ? choice.get("finish_reason").asText() : null;
            JsonNode usage = root.path("usage");
            Integer promptTokens = usage.hasNonNull("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null;
            Integer completionTokens = usage.hasNonNull("completion_tokens") ? usage.get("completion_tokens").asInt() : null;
            return new ChatResponse(msg, finishReason, new Usage(promptTokens, completionTokens));
        } catch (Exception e) {
            throw new ChatModelException("解析模型响应失败: " + e.getMessage(), 200);
        }
    }
}
