package com.aia.rag.openai;

import com.aia.rag.EmbeddingModel;
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
 * OpenAI 兼容的向量化模型适配器，可对接通义千问（DashScope compatible-mode）、
 * OpenAI、Ollama 等提供 {@code POST /v1/embeddings} 的服务。
 */
public final class OpenAiCompatibleEmbeddingModel implements EmbeddingModel {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int dimension;
    private final HttpClient httpClient;

    public OpenAiCompatibleEmbeddingModel(String baseUrl, String apiKey, String model, int dimension,
                                          HttpClient httpClient) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("向量维度必须大于 0");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.dimension = dimension;
        this.httpClient = httpClient;
    }

    @Override
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        if (texts.size() == 1) {
            body.put("input", texts.get(0));
        } else {
            ArrayNode input = body.putArray("input");
            texts.forEach(input::add);
        }

        HttpResponse<String> resp = send(body.toString());
        return parse(resp.body(), texts.size());
    }

    @Override
    public int dimension() {
        return dimension;
    }

    private HttpResponse<String> send(String body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/embeddings"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp;
        try {
            resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new EmbeddingException("请求向量化模型失败: " + e.getMessage(), -1);
        }
        if (resp.statusCode() != 200) {
            throw new EmbeddingException("向量化模型返回非 200 状态码 " + resp.statusCode() + ": " + resp.body(),
                    resp.statusCode());
        }
        return resp;
    }

    private List<float[]> parse(String body, int expected) {
        try {
            JsonNode data = MAPPER.readTree(body).path("data");
            List<float[]> vectors = new ArrayList<>(expected);
            for (int i = 0; i < expected; i++) {
                vectors.add(null);
            }
            for (JsonNode item : data) {
                int index = item.hasNonNull("index") ? item.get("index").asInt() : vectors.indexOf(null);
                JsonNode embedding = item.path("embedding");
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < vector.length; i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                if (vector.length != dimension) {
                    throw new EmbeddingException("向量维度不匹配: 配置 " + dimension + " 实际 " + vector.length, 200);
                }
                if (index < 0 || index >= vectors.size()) {
                    throw new EmbeddingException("响应 index 越界: " + index, 200);
                }
                vectors.set(index, vector);
            }
            if (vectors.contains(null)) {
                throw new EmbeddingException("响应缺少部分向量: 期望 " + expected + " 条", 200);
            }
            return vectors;
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("解析向量化响应失败: " + e.getMessage(), 200);
        }
    }
}
