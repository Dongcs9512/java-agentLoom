package io.github.dongcs9512.agentloom.rag;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.Map;
import java.util.Set;

/**
 * Elasticsearch 8.x 向量存储实现，通过 REST API 直连（无 ES 官方客户端依赖）。
 * 索引 mapping 固定为 text（文本）、dense_vector（向量，cosine 相似度）、flattened（metadata）。
 */
public final class ElasticsearchVectorStore implements VectorStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final String baseUrl;
    private final String index;
    private final int dimension;
    private final HttpClient httpClient;
    private volatile boolean ensured;

    public ElasticsearchVectorStore(String baseUrl, String index, int dimension, HttpClient httpClient) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("向量维度必须大于 0");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.index = index;
        this.dimension = dimension;
        this.httpClient = httpClient;
    }

    @Override
    public void upsert(List<VectorRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        for (VectorRecord r : records) {
            checkDimension(r.vector());
        }
        ensureIndex();
        StringBuilder bulk = new StringBuilder();
        for (VectorRecord r : records) {
            ObjectNode action = MAPPER.createObjectNode();
            action.putObject("index").put("_index", index).put("_id", r.id());
            bulk.append(action).append('\n').append(toSource(r)).append('\n');
        }
        sendBulk(bulk.toString());
    }

    @Override
    public List<SearchHit> search(VectorQuery query) {
        checkDimension(query.vector());
        ensureIndex();
        ObjectNode body = MAPPER.createObjectNode();
        ObjectNode knn = body.putObject("knn");
        knn.put("field", "vector");
        knn.set("query_vector", toArrayNode(query.vector()));
        knn.put("k", query.topK());
        knn.put("num_candidates", Math.max(query.topK(), 100));
        if (query.filter() != null && !query.filter().isEmpty()) {
            ArrayNode must = MAPPER.createArrayNode();
            for (Map.Entry<String, Object> e : query.filter().entrySet()) {
                ObjectNode term = MAPPER.createObjectNode();
                term.set("metadata." + e.getKey(), MAPPER.valueToTree(e.getValue()));
                must.add(MAPPER.createObjectNode().set("term", term));
            }
            ObjectNode bool = MAPPER.createObjectNode();
            bool.set("must", must);
            knn.set("filter", MAPPER.createObjectNode().set("bool", bool));
        }
        HttpResponse<String> resp = send("POST", "/" + index + "/_search", body.toString(), "application/json");
        return parseHits(resp.body());
    }

    @Override
    public void delete(List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        ensureIndex();
        StringBuilder bulk = new StringBuilder();
        for (String id : ids) {
            ObjectNode action = MAPPER.createObjectNode();
            action.putObject("delete").put("_index", index).put("_id", id);
            bulk.append(action).append('\n');
        }
        sendBulk(bulk.toString());
    }

    @Override
    public void update(List<VectorRecord> records) {
        upsert(records);
    }

    @Override
    public Set<VectorStoreCapability> capabilities() {
        return Set.of(VectorStoreCapability.METADATA_FILTER, VectorStoreCapability.BATCH_UPSERT);
    }

    private synchronized void ensureIndex() {
        if (ensured) {
            return;
        }
        if (head("/" + index) == 404) {
            ObjectNode body = MAPPER.createObjectNode();
            ObjectNode properties = body.putObject("mappings").putObject("properties");
            properties.putObject("text").put("type", "text");
            ObjectNode vector = properties.putObject("vector");
            vector.put("type", "dense_vector");
            vector.put("dims", dimension);
            vector.put("index", true);
            vector.put("similarity", "cosine");
            properties.putObject("metadata").put("type", "flattened");
            send("PUT", "/" + index, body.toString(), "application/json");
        }
        ensured = true;
    }

    private int head(String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(30))
                .build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            throw new VectorStoreException("请求 Elasticsearch 失败: " + e.getMessage(), -1);
        }
    }

    private void sendBulk(String body) {
        HttpResponse<String> resp = send("POST", "/" + index + "/_bulk?refresh=wait_for", body, "application/x-ndjson");
        try {
            JsonNode root = MAPPER.readTree(resp.body());
            if (root.path("errors").asBoolean(false)) {
                throw new VectorStoreException("Elasticsearch bulk 写入存在错误: " + resp.body(), resp.statusCode());
            }
        } catch (VectorStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new VectorStoreException("解析 bulk 响应失败: " + e.getMessage(), resp.statusCode());
        }
    }

    private HttpResponse<String> send(String method, String path, String body, String contentType) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", contentType)
                .timeout(Duration.ofSeconds(30))
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp;
        try {
            resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new VectorStoreException("请求 Elasticsearch 失败: " + e.getMessage(), -1);
        }
        if (resp.statusCode() >= 400) {
            throw new VectorStoreException("Elasticsearch 返回错误状态码 " + resp.statusCode() + ": " + resp.body(), resp.statusCode());
        }
        return resp;
    }

    private List<SearchHit> parseHits(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            List<SearchHit> hits = new ArrayList<>();
            for (JsonNode h : root.path("hits").path("hits")) {
                String id = h.path("_id").asText();
                float score = h.path("_score").isNumber() ? h.path("_score").floatValue() : 0f;
                JsonNode source = h.path("_source");
                String text = source.path("text").asText(null);
                Map<String, Object> metadata = source.path("metadata").isObject()
                        ? MAPPER.convertValue(source.path("metadata"), MAP_TYPE)
                        : Map.of();
                hits.add(new SearchHit(id, text, score, metadata));
            }
            return hits;
        } catch (Exception e) {
            throw new VectorStoreException("解析搜索响应失败: " + e.getMessage(), -1);
        }
    }

    private ObjectNode toSource(VectorRecord record) {
        ObjectNode source = MAPPER.createObjectNode();
        source.put("text", record.text());
        source.set("vector", toArrayNode(record.vector()));
        source.set("metadata", MAPPER.valueToTree(record.metadata()));
        return source;
    }

    private ArrayNode toArrayNode(float[] vector) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (float v : vector) {
            arr.add(v);
        }
        return arr;
    }

    private void checkDimension(float[] vector) {
        if (vector == null || vector.length != dimension) {
            throw new IllegalArgumentException("向量维度不匹配: 期望 " + dimension
                    + (vector == null ? "" : " 实际 " + vector.length));
        }
    }
}
