package io.github.dongcs9512.agentloom.rag.eval;

/**
 * 评测门禁阈值。低于任一阈值即视为回归。
 */
public record EvalThresholds(
        double retrievalHitRate,
        double keywordCoverage,
        double passRate) {

    public static EvalThresholds defaults() {
        return new EvalThresholds(0.95, 0.85, 0.90);
    }
}
