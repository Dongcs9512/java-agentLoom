package com.aia.rag.redis;

import com.aia.rag.VectorRecord;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisVectorCodecTest {

    @Test
    void float32UsesLittleEndian() {
        byte[] bytes = RedisVectorCodec.toFloat32(new float[]{1.0f});

        assertEquals(4, bytes.length);
        assertArrayEquals(new byte[]{0x00, 0x00, (byte) 0x80, 0x3f}, bytes);
    }

    @Test
    void float32RoundTrips() {
        float[] original = {0f, 1f, -2.5f, 0.125f, 1024.75f};

        assertArrayEquals(original, RedisVectorCodec.fromFloat32(RedisVectorCodec.toFloat32(original)), 0.0000001f);
    }

    @Test
    void attributesJsonSerializesMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("docId", "refund");
        metadata.put("lang", "zh");

        assertEquals("{\"docId\":\"refund\",\"lang\":\"zh\"}", RedisVectorCodec.attributesJson(metadata));
    }

    @Test
    void filterExpressionUsesEqualityOnAttributes() {
        assertEquals(".docId == \"refund\"", RedisVectorCodec.filterExpression(Map.of("docId", "refund")));
    }

    @Test
    void filterExpressionJoinsMultipleConditions() {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("docId", "refund");
        filter.put("lang", "zh");

        assertEquals(".docId == \"refund\" && .lang == \"zh\"", RedisVectorCodec.filterExpression(filter));
    }

    @Test
    void filterExpressionIsEmptyWhenNoConditions() {
        assertEquals("", RedisVectorCodec.filterExpression(Map.of()));
        assertEquals("", RedisVectorCodec.filterExpression(null));
    }

    @Test
    void filterExpressionEscapesQuotes() {
        assertEquals(".title == \"a\\\"b\"", RedisVectorCodec.filterExpression(Map.of("title", "a\"b")));
    }

    @Test
    void recordAttributesCarryTextAndMetadata() {
        VectorRecord record = new VectorRecord("refund#0", "退款政策", new float[]{1f}, Map.of("docId", "refund"));

        String json = RedisVectorCodec.recordAttributes(record);

        assertEquals("{\"text\":\"退款政策\",\"docId\":\"refund\"}", json);
    }

    @Test
    void parseAttributesSplitsTextFromMetadata() {
        ParsedAttributes parsed = RedisVectorCodec.parseAttributes("{\"text\":\"退款政策\",\"docId\":\"refund\"}");

        assertEquals("退款政策", parsed.text());
        assertEquals(Map.of("docId", "refund"), parsed.metadata());
    }

    @Test
    void parseAttributesHandlesMissingMetadata() {
        ParsedAttributes parsed = RedisVectorCodec.parseAttributes("{\"text\":\"只有正文\"}");

        assertEquals("只有正文", parsed.text());
        assertEquals(Map.of(), parsed.metadata());
    }
}
