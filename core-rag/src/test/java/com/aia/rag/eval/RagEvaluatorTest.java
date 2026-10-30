package com.aia.rag.eval;

import com.aia.rag.SearchHit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagEvaluatorTest {

    private static final EvalCase CASE = new EvalCase("c1",
            "试用期被辞退有赔偿吗",
            List.of("probation-termination"),
            List.of("证明", "赔偿金"));

    private static SearchHit hit(String id) {
        return new SearchHit(id, "正文", 0.9f, Map.of("docId", id));
    }

    @Test
    void retrievalHitWhenExpectedDocAppears() {
        EvalCaseResult result = RagEvaluator.score(CASE, List.of(hit("probation-termination")), "需要证明不符合录用条件，可主张赔偿金");

        assertTrue(result.retrievalHit());
        assertEquals(List.of("probation-termination"), result.hitDocIds());
    }

    @Test
    void retrievalMissWhenExpectedDocAbsent() {
        EvalCaseResult result = RagEvaluator.score(CASE, List.of(hit("other-doc")), "赔偿金");

        assertFalse(result.retrievalHit());
    }

    @Test
    void keywordCoverageCountsPresentKeywords() {
        EvalCaseResult result = RagEvaluator.score(CASE, List.of(hit("probation-termination")), "需要证明不符合录用条件");

        assertEquals(0.5, result.keywordCoverage(), 0.0001);
        assertEquals(List.of("赔偿金"), result.missingKeywords());
    }

    @Test
    void fullCoverageWhenAllKeywordsPresent() {
        EvalCaseResult result = RagEvaluator.score(CASE, List.of(), "用人单位需要证明，否则支付赔偿金");

        assertEquals(1.0, result.keywordCoverage(), 0.0001);
        assertTrue(result.missingKeywords().isEmpty());
    }

    @Test
    void nullAnswerGivesZeroCoverage() {
        EvalCaseResult result = RagEvaluator.score(CASE, List.of(), null);

        assertEquals(0.0, result.keywordCoverage(), 0.0001);
        assertEquals(2, result.missingKeywords().size());
    }

    @Test
    void reportAggregatesMetrics() {
        EvalReport report = new EvalReport("测试集", List.of(
                new EvalCaseResult("c1", "q1", true, List.of("a"), 1.0, List.of(), "ans"),
                new EvalCaseResult("c2", "q2", false, List.of(), 0.2, List.of("x"), "ans")));

        assertEquals(0.5, report.retrievalHitRate(), 0.0001);
        assertEquals(0.6, report.averageKeywordCoverage(), 0.0001);
        assertEquals(0.5, report.passRate(0.6), 0.0001);
    }

    @Test
    void emptyReportDoesNotDivideByZero() {
        EvalReport report = new EvalReport("空", List.of());

        assertEquals(0.0, report.retrievalHitRate(), 0.0001);
        assertEquals(0.0, report.averageKeywordCoverage(), 0.0001);
    }
}
