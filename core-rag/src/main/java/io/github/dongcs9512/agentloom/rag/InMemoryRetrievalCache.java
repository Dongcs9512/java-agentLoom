package io.github.dongcs9512.agentloom.rag;

import io.github.dongcs9512.agentloom.core.session.QuestionNormalizer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 有容量上限的 LRU 检索缓存，带 TTL 兜底（防止多实例部署时别的实例写了新文档这里看不到）。
 */
public final class InMemoryRetrievalCache implements RetrievalCache {

    private final int capacity;
    private final long ttlMillis;
    private final Map<String, CachedHits> entries;

    private record CachedHits(List<SearchHit> hits, long expiresAt) {
    }

    public InMemoryRetrievalCache(int capacity, long ttlSeconds) {
        this.capacity = Math.max(1, capacity);
        this.ttlMillis = Math.max(0, ttlSeconds) * 1000L;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedHits> eldest) {
                return size() > InMemoryRetrievalCache.this.capacity;
            }
        };
    }

    @Override
    public synchronized Optional<List<SearchHit>> get(String scope, String query) {
        CachedHits entry = entries.get(key(scope, query));
        if (entry == null) {
            return Optional.empty();
        }
        if (ttlMillis > 0 && System.currentTimeMillis() > entry.expiresAt()) {
            entries.remove(key(scope, query));
            return Optional.empty();
        }
        return Optional.of(entry.hits());
    }

    @Override
    public synchronized void put(String scope, String query, List<SearchHit> hits) {
        String normalized = QuestionNormalizer.normalize(query);
        if (normalized.isEmpty()) {
            return;
        }
        long expiresAt = ttlMillis > 0 ? System.currentTimeMillis() + ttlMillis : Long.MAX_VALUE;
        entries.put(key(scope, query), new CachedHits(List.copyOf(hits), expiresAt));
    }

    @Override
    public synchronized void clear() {
        entries.clear();
    }

    public synchronized int size() {
        return entries.size();
    }

    private static String key(String scope, String query) {
        return (scope == null ? "" : scope) + "|" + QuestionNormalizer.normalize(query);
    }
}
