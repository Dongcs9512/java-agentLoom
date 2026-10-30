package com.aia.rag;

import com.aia.core.obs.MetricsRecorder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 两级检索缓存：L1 进程内（微秒级）+ L2 共享存储（Redis，毫秒级）。
 *
 * <p>命中 L2 会回填 L1，所以热门问题在本机只会多走一次网络。
 * 对于带版本号的 L2，还会定期比对版本号：别的实例写入了知识库时，清掉自己的 L1，
 * 避免多实例部署下读到过期结果。
 */
public final class CompositeRetrievalCache implements RetrievalCache {

    private static final long DEFAULT_VERSION_CHECK_INTERVAL_MS = 2000;

    private final RetrievalCache l1;
    private final RetrievalCache l2;
    private final VersionedRetrievalCache versionedL2;
    private final long checkIntervalMs;
    private final MetricsRecorder metrics;

    private volatile long knownVersion;
    private volatile long lastCheckAt;

    public CompositeRetrievalCache(RetrievalCache l1, RetrievalCache l2) {
        this(l1, l2, DEFAULT_VERSION_CHECK_INTERVAL_MS);
    }

    public CompositeRetrievalCache(RetrievalCache l1, RetrievalCache l2, long checkIntervalMs) {
        this(l1, l2, checkIntervalMs, MetricsRecorder.noop());
    }

    public CompositeRetrievalCache(RetrievalCache l1, RetrievalCache l2, long checkIntervalMs,
                                   MetricsRecorder metrics) {
        this.l1 = l1;
        this.l2 = l2;
        this.versionedL2 = l2 instanceof VersionedRetrievalCache v ? v : null;
        this.checkIntervalMs = Math.max(0, checkIntervalMs);
        this.metrics = metrics == null ? MetricsRecorder.noop() : metrics;
        if (versionedL2 != null) {
            try {
                this.knownVersion = versionedL2.version();
            } catch (RuntimeException ignored) {
                this.knownVersion = 0;
            }
        }
    }

    @Override
    public Optional<List<SearchHit>> get(String scope, String query) {
        syncVersion();
        Optional<List<SearchHit>> local = l1.get(scope, query);
        if (local.isPresent()) {
            metrics.count("aia.cache.retrieval.level", Map.of("level", "l1"));
            return local;
        }
        Optional<List<SearchHit>> shared = l2.get(scope, query);
        if (shared.isPresent()) {
            metrics.count("aia.cache.retrieval.level", Map.of("level", "l2"));
            l1.put(scope, query, shared.get());
        }
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
        if (versionedL2 != null) {
            knownVersion = versionedL2.version();
            lastCheckAt = System.currentTimeMillis();
        }
    }

    /**
     * 按间隔检查共享缓存的版本号，变了就清本地缓存——别的实例写入过知识库。
     */
    private void syncVersion() {
        if (versionedL2 == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCheckAt < checkIntervalMs) {
            return;
        }
        lastCheckAt = now;
        try {
            long current = versionedL2.version();
            if (current != knownVersion) {
                l1.clear();
                knownVersion = current;
            }
        } catch (RuntimeException ignored) {
            // 共享缓存暂时不可用时，继续用本地缓存
        }
    }
}
