package io.github.dongcs9512.agentloom.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeRetrievalCacheTest {

    /** 记录 get 次数，用来验证 L1 命中时不会再问 L2。 */
    static class CountingCache implements RetrievalCache {
        final AtomicInteger gets = new AtomicInteger();
        private final InMemoryRetrievalCache delegate = new InMemoryRetrievalCache(50, 0);

        @Override
        public Optional<List<SearchHit>> get(String scope, String query) {
            gets.incrementAndGet();
            return delegate.get(scope, query);
        }

        @Override
        public void put(String scope, String query, List<SearchHit> hits) {
            delegate.put(scope, query, hits);
        }

        @Override
        public void clear() {
            delegate.clear();
        }
    }

    private static List<SearchHit> hits() {
        return List.of(new SearchHit("d1#0", "正文", 0.9f, Map.of("docId", "d1")));
    }

    @Test
    void l1HitSkipsL2() {
        CountingCache l1 = new CountingCache();
        CountingCache l2 = new CountingCache();
        CompositeRetrievalCache cache = new CompositeRetrievalCache(l1, l2);
        cache.put("topK=3", "退款怎么退", hits());

        cache.get("topK=3", "退款怎么退");

        assertEquals(1, l1.gets.get());
        assertEquals(0, l2.gets.get(), "L1 命中就不该再问 L2");
    }

    @Test
    void l2HitWarmsL1() {
        CountingCache l1 = new CountingCache();
        CountingCache l2 = new CountingCache();
        l2.put("topK=3", "退款怎么退", hits());
        CompositeRetrievalCache cache = new CompositeRetrievalCache(l1, l2);

        assertTrue(cache.get("topK=3", "退款怎么退").isPresent());
        int l2CallsAfterFirst = l2.gets.get();

        cache.get("topK=3", "退款怎么退");
        assertEquals(l2CallsAfterFirst, l2.gets.get(), "回填后应直接命中 L1");
    }

    @Test
    void putWritesBothLevels() {
        CountingCache l1 = new CountingCache();
        CountingCache l2 = new CountingCache();
        CompositeRetrievalCache cache = new CompositeRetrievalCache(l1, l2);

        cache.put("topK=3", "q", hits());

        assertTrue(l1.get("topK=3", "q").isPresent());
        assertTrue(l2.get("topK=3", "q").isPresent());
    }

    @Test
    void clearEmptiesBothLevels() {
        CountingCache l1 = new CountingCache();
        CountingCache l2 = new CountingCache();
        CompositeRetrievalCache cache = new CompositeRetrievalCache(l1, l2);
        cache.put("topK=3", "q", hits());

        cache.clear();

        assertTrue(l1.get("topK=3", "q").isEmpty());
        assertTrue(l2.get("topK=3", "q").isEmpty());
    }
}
