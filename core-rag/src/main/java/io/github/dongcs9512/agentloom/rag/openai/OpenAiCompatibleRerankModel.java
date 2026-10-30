package io.github.dongcs9512.agentloom.rag.openai;

import io.github.dongcs9512.agentloom.rag.RerankModel;
import io.github.dongcs9512.agentloom.rag.RerankResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容的重排接口适配器（{@code POST /v1/rerank}），
 * 可对接硅基流动、Jina、Cohere 等提供该协议的供应商。
 */
public final class OpenAiCompatibleRerankModel implements RerankModel {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;

    public OpenAiCompatibleRerankModel(String baseUrl, String apiKey, String model, HttpClient httpClient) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = httpClient;
    }

    @Override
    public List<RerankResult> rerank(String query, List<String> documents, int topN) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("query", query);
        ArrayNode docs = body.putArray("documents");
        documents.forEach(docs::add);
        body.put("top_n", Math.max(1, topN));

        HttpResponse<String> resp = send(body.toString());
        return parse(resp.body());
    }

    private HttpResponse<String> send(String body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/rerank"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp;
        try {
            resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RerankException("请求重排模型失败: " + e.getMessage(), -1);
        }
        if (resp.statusCode() != 200) {
            throw new RerankException("重排模型返回非 200 状态码 " + resp.statusCode() + ": " + resp.body(),
                    resp.statusCode());
        }
        return resp;
    }

    private List<RerankResult> parse(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            List<RerankResult> results = new ArrayList<>();
            for (JsonNode item : root.path("results")) {
                JsonNode score = item.hasNonNull("relevance_score") ? item.get("relevance_score") : item.path("score");
                results.add(new RerankResult(item.path("index").asInt(), score.asDouble()));
            }
            results.sort((a, b) -> Double.compare(b.score(), a.score()));
            return results;
        } catch (Exception e) {
            throw new RerankException("解析重排响应失败: " + e.getMessage(), 200);
        }
    }
}
