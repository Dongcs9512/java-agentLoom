package com.aia.rag;

import java.util.List;
import java.util.Optional;

/**
 * 两级检索缓存：L1 进程内（微秒级）+ L2 共享存储（Redis，毫秒级）。
 *
 * <p>命中 L2 会回填 L1，所以热门问题在本机只会多走一次网络，
 * 后续既享受本地速度，又多实例共享命中率。
 */
public final class CompositeRetrievalCache implements RetrievalCache {

    private final RetrievalCache l1;
    private final RetrievalCache l2;

    public CompositeRetrievalCache(RetrievalCache l1, RetrievalCache l2) {
        this.l1 = l1;
        this.l2 = l2;
    }

    @Override
    public Optional<List<SearchHit>> get(String scope, String query) {
        Optional<List<SearchHit>> local = l1.get(scope, query);
        if (local.isPresent()) {
            return local;
        }
        Optional<List<SearchHit>> shared = l2.get(scope, query);
        shared.ifPresent(hits -> l1.put(scope, query, hits));
        return shared;
    }

    @Override
    public void put(String scope, String query, List<SearchHit> hits) {
        l1.put(scope, query, hits);
        l2.put(scope, query, hits);
    }

    @Override
    public void clear() {
        l1.clear();
        l2.clear();
    }
}
