package io.github.dongcs9512.agentloom.rag.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalGateTest {

    private static EvalReport report(boolean hit1, boolean hit2, double cov1, double cov2) {
        return new EvalReport("测试集", List.of(
                new EvalCaseResult("c1", "q1", hit1, List.of(), cov1, List.of(), "a"),
                new EvalCaseResult("c2", "q2", hit2, List.of(), cov2, List.of(), "a")));
    }

    @Test
    void passesWhenAllMetricsMeetThresholds() {
        EvalThresholds thresholds = new EvalThresholds(0.9, 0.8, 0.8);

        GateResult result = EvalGate.check(report(true, true, 1.0, 0.9), thresholds);

        assertTrue(result.passed(), result.violations().toString());
    }

    @Test
    void failsWhenRetrievalHitRateDrops() {
        EvalThresholds thresholds = new EvalThresholds(0.95, 0.5, 0.5);

        GateResult result = EvalGate.check(report(false, true, 1.0, 1.0), thresholds);

        assertFalse(result.passed());
        assertTrue(result.violations().stream().anyMatch(v -> v.contains("检索命中率")), result.violations().toString());
    }

    @Test
    void failsWhenKeywordCoverageDrops() {
        EvalThresholds thresholds = new EvalThresholds(0.5, 0.9, 0.5);

        GateResult result = EvalGate.check(report(true, true, 0.5, 0.5), thresholds);

        assertFalse(result.passed());
        assertTrue(result.violations().stream().anyMatch(v -> v.contains("关键词覆盖")), result.violations().toString());
    }

    @Test
    void failsWhenPassRateDrops() {
        EvalThresholds thresholds = new EvalThresholds(0.5, 0.5, 0.9);

        GateResult result = EvalGate.check(report(true, false, 1.0, 0.1), thresholds);

        assertFalse(result.passed());
    }

    @Test
    void reportsEveryViolationNotJustTheFirst() {
        EvalThresholds thresholds = new EvalThresholds(0.95, 0.95, 0.95);

        GateResult result = EvalGate.check(report(false, false, 0.1, 0.1), thresholds);

        assertTrue(result.violations().size() >= 2, result.violations().toString());
    }

    @Test
    void emptyReportFailsTheGate() {
        GateResult result = EvalGate.check(new EvalReport("空", List.of()), new EvalThresholds(0.5, 0.5, 0.5));

        assertFalse(result.passed());
    }
}
