package io.github.dongcs9512.agentloom.rag.redis;

import io.github.dongcs9512.agentloom.rag.VectorRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Redis Vector Set 的编码工具：向量走 float32 小端二进制，
 * 元数据以 JSON 存为属性，过滤条件翻译成 Vector Set 的 FILTER 表达式。
 */
final class RedisVectorCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RedisVectorCodec() {
    }

    static byte[] toFloat32(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    static float[] fromFloat32(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }

    static String attributesJson(Map<String, Object> metadata) {
        try {
            return MAPPER.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (Exception e) {
            throw new IllegalArgumentException("元数据无法序列化: " + e.getMessage(), e);
        }
    }

    static String filterExpression(Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return "";
        }
        List<String> conditions = new ArrayList<>();
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            conditions.add("." + entry.getKey() + " == " + jsonLiteral(entry.getValue()));
        }
        return String.join(" && ", conditions);
    }

    private static String jsonLiteral(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("过滤值无法序列化: " + e.getMessage(), e);
        }
    }

    /**
     * Vector Set 只存元素名和属性，正文也一并写进属性，读取时再拆开。
     */
    static String recordAttributes(VectorRecord record) {
        Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        attributes.put("text", record.text());
        if (record.metadata() != null) {
            attributes.putAll(record.metadata());
        }
        return attributesJson(attributes);
    }

    static ParsedAttributes parseAttributes(String json) {
        try {
            Map<String, Object> attributes = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
            Object text = attributes.remove("text");
            return new ParsedAttributes(text == null ? "" : String.valueOf(text), attributes);
        } catch (Exception e) {
            throw new IllegalStateException("解析 Vector Set 属性失败: " + e.getMessage(), e);
        }
    }
}
