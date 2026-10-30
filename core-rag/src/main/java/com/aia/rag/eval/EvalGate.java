package com.aia.rag.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 回归门禁：拿评测结果对比阈值，任何一项掉线都判为不通过，并列出所有未达标项。
 */
public final class EvalGate {

    private static final double PASS_RATE_THRESHOLD_FOR_CASES = 0.6;

    private EvalGate() {
    }

    public static GateResult check(EvalReport report, EvalThresholds thresholds) {
        List<String> violations = new ArrayList<>();
        if (report.results().isEmpty()) {
            violations.add("评测集为空，无法判断");
            return new GateResult(false, violations);
        }

        compare(violations, "检索命中率", report.retrievalHitRate(), thresholds.retrievalHitRate());
        compare(violations, "关键词覆盖率", report.averageKeywordCoverage(), thresholds.keywordCoverage());
        compare(violations, "通过率", report.passRate(PASS_RATE_THRESHOLD_FOR_CASES), thresholds.passRate());

        return new GateResult(violations.isEmpty(), violations);
    }

    private static void compare(List<String> violations, String name, double actual, double expected) {
        if (actual + 1e-9 < expected) {
            violations.add(String.format(Locale.ROOT, "%s %.0f%% 低于阈值 %.0f%%",
                    name, actual * 100, expected * 100));
        }
    }
}
