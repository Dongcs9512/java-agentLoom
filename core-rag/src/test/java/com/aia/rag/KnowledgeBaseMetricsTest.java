package com.aia.rag;

import com.aia.core.obs.MetricsRecorder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeBaseMetricsTest {

    static class RecordingMetrics implements MetricsRecorder {
        final Map<String, Integer> counters = new HashMap<>();
        final List<Long> timings = new ArrayList<>();

        @Override
        public void count(String name, Map<String, String> tags) {
            counters.merge(name + tags.getOrDefault("result", ""), 1, Integer::sum);
        }

        @Override
        public void time(String name, long millis, Map<String, String> tags) {
            timings.add(millis);
        }
    }

    static class FakeEmbedder implements EmbeddingModel {
        @Override
        public float[] embed(String text) {
            return new float[]{1, 0};
        }

        @Override
        public int dimension() {
            return 2;
        }
    }

    @Test
    void countsCacheHitAndMiss() {
        RecordingMetrics metrics = new RecordingMetrics();
        KnowledgeBase kb = new KnowledgeBase(new FakeEmbedder(), new InMemoryVectorStore(2), new TextChunker(),
                500, 50, null, new InMemoryRetrievalCache(10, 0), metrics);
        kb.addDocument("d1", "退款政策：签收后 7 天内可无理由退款。");

        kb.search("退款", 3);
        kb.search("退款", 3);

        assertEquals(1, metrics.counters.getOrDefault("aia.cache.retrievalmiss", 0));
        assertEquals(1, metrics.counters.getOrDefault("aia.cache.retrievalhit", 0));
    }

    @Test
    void recordsSearchDurationOnlyOnMiss() {
        RecordingMetrics metrics = new RecordingMetrics();
        KnowledgeBase kb = new KnowledgeBase(new FakeEmbedder(), new InMemoryVectorStore(2), new TextChunker(),
                500, 50, null, new InMemoryRetrievalCache(10, 0), metrics);
        kb.addDocument("d1", "退款政策：签收后 7 天内可无理由退款。");

        kb.search("退款", 3);
        kb.search("退款", 3);

        assertEquals(1, metrics.timings.size(), "命中缓存不应该再记录一次检索耗时");
        assertTrue(metrics.timings.get(0) >= 0);
    }
}
