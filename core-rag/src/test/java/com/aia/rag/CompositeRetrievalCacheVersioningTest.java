package com.aia.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeRetrievalCacheVersioningTest {

    /** 模拟共享缓存：带版本号，clear 时版本 +1。 */
    static class VersionedSharedCache implements VersionedRetrievalCache {
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
    void otherInstanceWriteInvalidatesLocalCache() {
        InMemoryRetrievalCache l1 = new InMemoryRetrievalCache(50, 0);
        VersionedSharedCache shared = new VersionedSharedCache();
        CompositeRetrievalCache cache = new CompositeRetrievalCache(l1, shared, 0);

        cache.put("topK=4", "试用期辞退", hits("旧内容"));
        assertTrue(cache.get("topK=4", "试用期辞退").orElseThrow().get(0).text().equals("旧内容"));

        // 另一个实例写入知识库：清共享缓存并递增版本号
        shared.clear();
        shared.put("topK=4", "试用期辞退", hits("新内容"));

        String latest = cache.get("topK=4", "试用期辞退").orElseThrow().get(0).text();

        assertTrue(latest.equals("新内容"), "本地缓存应因版本变化而失效，实际: " + latest);
    }

    @Test
    void sameVersionKeepsLocalCache() {
        InMemoryRetrievalCache l1 = new InMemoryRetrievalCache(50, 0);
        VersionedSharedCache shared = new VersionedSharedCache();
        CompositeRetrievalCache cache = new CompositeRetrievalCache(l1, shared, 0);
        cache.put("topK=4", "q", hits("内容"));

        // 版本不变时，即使共享缓存被清，本地仍应命中（这是性能设计）
        shared.get("topK=4", "q");

        assertTrue(cache.get("topK=4", "q").isPresent());
    }
}
