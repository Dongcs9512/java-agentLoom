package io.github.dongcs9512.agentloom.rag;

import io.github.dongcs9512.agentloom.core.obs.MetricsRecorder;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompositeRetrievalCacheMetricsTest {

    static class RecordingMetrics implements MetricsRecorder {
        final Map<String, Long> values = new HashMap<>();

        @Override
        public void count(String name, long amount, Map<String, String> tags) {
            values.merge(name + tags, amount, Long::sum);
        }
    }

    static class SharedCache implements VersionedRetrievalCache {
        final AtomicLong version = new AtomicLong();
        private final InMemoryRetrievalCache delegate = new InMemoryRetrievalCache(50, 0);

        @Override
        public long version() {
            return version.get();
        }

        @Override
        public Optional<List<SearchHit>> get(String scope, String query) {
            return delegate.get(scope, query);
        }

        @Override
        public void put(String scope, String query, List<SearchHit> hits) {
            delegate.put(scope, query, hits);
        }

        @Override
        public void clear() {
            delegate.clear();
            version.incrementAndGet();
        }
    }

    private static List<SearchHit> hits(String text) {
        return List.of(new SearchHit("d1#0", text, 0.9f, Map.of("docId", "d1")));
    }

    @Test
    void countsL1AndL2HitsSeparately() {
        RecordingMetrics metrics = new RecordingMetrics();
        InMemoryRetrievalCache l1 = new InMemoryRetrievalCache(50, 0);
        SharedCache shared = new SharedCache();
        CompositeRetrievalCache cache = new CompositeRetrievalCache(l1, shared, 0, metrics);

        cache.put("topK=4", "试用期辞退", hits("内容"));
        cache.get("topK=4", "试用期辞退");           // L1 命中

        // 换个实例：L1 空，L2 有
        InMemoryRetrievalCache coldL1 = new InMemoryRetrievalCache(50, 0);
        CompositeRetrievalCache other = new CompositeRetrievalCache(coldL1, shared, 0, metrics);
        other.get("topK=4", "试用期辞退");           // L2 命中

        assertEquals(1L, metrics.values.get("agentloom.cache.retrieval.level{level=l1}"));
        assertEquals(1L, metrics.values.get("agentloom.cache.retrieval.level{level=l2}"));
    }
}
