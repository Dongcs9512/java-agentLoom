package io.github.dongcs9512.agentloom.rag.eval;

import io.github.dongcs9512.agentloom.core.session.QuestionNormalizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 缓存收益模拟：把一段流量依次灌进缓存，统计命中率与误命中率。
 *
 * <p>精确缓存只认归一化后完全相同的问法；语义缓存按向量相似度认「意思一样」，
 * 但阈值过低会把不同案件混为一谈 —— 对法律咨询这种强个性化场景是致命的。
 */
public final class CacheHitSimulator {

    private CacheHitSimulator() {
    }

    public static CacheSimulation simulateExact(List<CacheTrafficItem> traffic, int capacity) {
        Map<String, String> cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > capacity;
            }
        };
        int hits = 0;
        int correct = 0;
        for (CacheTrafficItem item : traffic) {
            String key = QuestionNormalizer.normalize(item.text());
            String cachedGroup = cache.get(key);
            if (cachedGroup != null) {
                hits++;
                if (cachedGroup.equals(item.groupId())) {
                    correct++;
                }
            } else {
                cache.put(key, item.groupId());
            }
        }
        return CacheSimulation.of("exact", traffic.size(), hits, correct);
    }

    /**
     * @param vectors  每个不重复问法的向量
     * @param threshold 相似度达到多少就认为可以复用
     */
    public static CacheSimulation simulateSemantic(List<CacheTrafficItem> traffic, Map<String, float[]> vectors,
                                                   double threshold, int capacity) {
        List<String> groups = new ArrayList<>();
        List<float[]> cached = new ArrayList<>();
        int hits = 0;
        int correct = 0;

        for (CacheTrafficItem item : traffic) {
            float[] vector = vectors.get(item.text());
            if (vector == null) {
                continue;
            }
            int bestIndex = -1;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < cached.size(); i++) {
                double score = cosine(vector, cached.get(i));
                if (score > bestScore) {
                    bestScore = score;
                    bestIndex = i;
                }
            }
            if (bestIndex >= 0 && bestScore >= threshold) {
                hits++;
                if (groups.get(bestIndex).equals(item.groupId())) {
                    correct++;
                }
                continue;
            }
            if (cached.size() >= capacity) {
                groups.remove(0);
                cached.remove(0);
            }
            groups.add(item.groupId());
            cached.add(vector);
        }
        return CacheSimulation.of("semantic@" + threshold, traffic.size(), hits, correct);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
