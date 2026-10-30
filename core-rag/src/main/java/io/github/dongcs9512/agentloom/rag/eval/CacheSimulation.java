package io.github.dongcs9512.agentloom.rag.eval;

import java.util.Locale;

/**
 * @param hits          命中次数（含误命中）
 * @param correctHits   命中且确实是同一问法的次数
 * @param precision     命中里正确的比例
 * @param effectiveHitRate 真正省下模型调用、而且答案可复用的比例
 */
public record CacheSimulation(
        String strategy,
        int requests,
        int hits,
        int correctHits,
        int wrongHits,
        double hitRate,
        double precision,
        double effectiveHitRate) {

    public static CacheSimulation of(String strategy, int requests, int hits, int correctHits) {
        int wrong = hits - correctHits;
        return new CacheSimulation(strategy, requests, hits, correctHits, wrong,
                requests == 0 ? 0 : (double) hits / requests,
                hits == 0 ? 1.0 : (double) correctHits / hits,
                requests == 0 ? 0 : (double) correctHits / requests);
    }

    public String summary() {
        return String.format(Locale.ROOT,
                "%-18s 命中率 %5.1f%%  精度 %5.1f%%  有效命中 %5.1f%%  (命中 %d / 误命中 %d / 共 %d)",
                strategy, hitRate * 100, precision * 100, effectiveHitRate * 100, hits, wrongHits, requests);
    }
}
