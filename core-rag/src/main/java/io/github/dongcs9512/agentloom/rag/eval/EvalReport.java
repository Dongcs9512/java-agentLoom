package io.github.dongcs9512.agentloom.rag.eval;

import java.util.List;
import java.util.Locale;

/**
 * 评测汇总。检索命中率衡量「找得到」，关键词覆盖率衡量「答得实」。
 */
public record EvalReport(String name, List<EvalCaseResult> results) {

    public EvalReport {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public double retrievalHitRate() {
        if (results.isEmpty()) {
            return 0.0;
        }
        long hits = results.stream().filter(EvalCaseResult::retrievalHit).count();
        return (double) hits / results.size();
    }

    public double averageKeywordCoverage() {
        if (results.isEmpty()) {
            return 0.0;
        }
        return results.stream().mapToDouble(EvalCaseResult::keywordCoverage).average().orElse(0.0);
    }

    /**
     * 通过 = 检索命中且关键词覆盖率达到阈值。
     */
    public double passRate(double keywordThreshold) {
        if (results.isEmpty()) {
            return 0.0;
        }
        long passed = results.stream()
                .filter(r -> r.retrievalHit() && r.keywordCoverage() >= keywordThreshold)
                .count();
        return (double) passed / results.size();
    }

    public List<EvalCaseResult> failures(double keywordThreshold) {
        return results.stream()
                .filter(r -> !r.retrievalHit() || r.keywordCoverage() < keywordThreshold)
                .toList();
    }

    public String summary(double keywordThreshold) {
        return String.format(Locale.ROOT,
                "评测集「%s」共 %d 题：检索命中率 %.0f%%（%d/%d），平均关键词覆盖 %.0f%%，通过率 %.0f%%（阈值 %.0f%%）",
                name,
                results.size(),
                retrievalHitRate() * 100,
                results.stream().filter(EvalCaseResult::retrievalHit).count(),
                results.size(),
                averageKeywordCoverage() * 100,
                passRate(keywordThreshold) * 100,
                keywordThreshold * 100);
    }
}
