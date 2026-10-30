package com.aia.rag.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CacheHitSimulatorTest {

    private static CacheTrafficItem item(String group, String text) {
        return new CacheTrafficItem(group, text);
    }

    @Test
    void exactCacheOnlyHitsIdenticalWording() {
        List<CacheTrafficItem> traffic = List.of(
                item("faq-1", "劳动仲裁时效是多久"),
                item("faq-1", "劳动仲裁时效是多久"),
                item("faq-1", "仲裁超过一年还能申请吗"));

        CacheSimulation result = CacheHitSimulator.simulateExact(traffic, 100);

        assertEquals(1, result.hits(), "只有完全相同的问法才命中");
        assertEquals(1, result.correctHits());
    }

    @Test
    void exactCacheIgnoresPunctuationAndSpacing() {
        List<CacheTrafficItem> traffic = List.of(
                item("faq-1", "劳动仲裁时效是多久"),
                item("faq-1", "劳动仲裁时效是多久？"),
                item("faq-1", " 劳动仲裁时效是多久 "));

        assertEquals(2, CacheHitSimulator.simulateExact(traffic, 100).hits());
    }

    @Test
    void capacityEvictionStopsHits() {
        List<CacheTrafficItem> traffic = List.of(
                item("faq-1", "问题一"),
                item("faq-2", "问题二"),
                item("faq-1", "问题一"));

        assertEquals(1, CacheHitSimulator.simulateExact(traffic, 10).hits());
        assertEquals(0, CacheHitSimulator.simulateExact(traffic, 1).hits(), "容量只有 1 时会被挤出");
    }

    @Test
    void semanticCacheReusesParaphrases() {
        Map<String, float[]> vectors = Map.of(
                "仲裁时效多久", new float[]{1, 0},
                "仲裁有没有时间限制", new float[]{0.99f, 0.14f});
        List<CacheTrafficItem> traffic = List.of(
                item("faq-1", "仲裁时效多久"),
                item("faq-1", "仲裁有没有时间限制"));

        CacheSimulation result = CacheHitSimulator.simulateSemantic(traffic, vectors, 0.9, 100);

        assertEquals(1, result.hits());
        assertEquals(1, result.correctHits());
        assertEquals(1.0, result.precision(), 0.0001);
    }

    @Test
    void lowThresholdCausesWrongHitsOnDistinctCases() {
        Map<String, float[]> vectors = Map.of(
                "劳动仲裁时效多久", new float[]{1, 0},
                "我上个月被公司辞退了想要赔偿", new float[]{0.8f, 0.6f});
        List<CacheTrafficItem> traffic = List.of(
                item("faq-1", "劳动仲裁时效多久"),
                item("tail-1", "我上个月被公司辞退了想要赔偿"));

        CacheSimulation loose = CacheHitSimulator.simulateSemantic(traffic, vectors, 0.75, 100);
        CacheSimulation strict = CacheHitSimulator.simulateSemantic(traffic, vectors, 0.95, 100);

        assertEquals(1, loose.hits(), "阈值低时个案描述被误判成相似问题");
        assertEquals(0, loose.correctHits());
        assertEquals(0, strict.hits(), "阈值高时不会误命中");
    }
}
