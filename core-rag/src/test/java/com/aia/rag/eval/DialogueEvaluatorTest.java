package com.aia.rag.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueEvaluatorTest {

    @Test
    void turnScoresKeywordCoverage() {
        var turn = DialogueEvaluator.scoreTurn("公司把我辞退了", "请问具体是什么事？发生在什么时候？",
                List.of("什么事", "什么时候"));

        assertEquals(1.0, turn.keywordCoverage(), 0.0001);
        assertTrue(turn.missingKeywords().isEmpty());
    }

    @Test
    void turnNormalizesNumbersBeforeMatching() {
        var turn = DialogueEvaluator.scoreTurn("赔多少", "可以主张 200% 的赔偿", List.of("百分之二百"));

        assertEquals(1.0, turn.keywordCoverage(), 0.0001);
    }

    @Test
    void resultPassesOnlyWhenSlotsFilledAndCoverageMet() {
        var turns = List.of(new DialogueResult.DialogueTurnResult("q", "a", 1.0, List.of()));

        DialogueResult ok = DialogueResult.of(caseOf(List.of("incident", "when"), List.of("赔偿金")),
                turns, List.of("incident", "when"), List.of("incident", "when"), 1.0, 0.6);
        DialogueResult missingSlot = DialogueResult.of(caseOf(List.of("incident", "when"), List.of("赔偿金")),
                turns, List.of("incident", "when"), List.of("incident"), 1.0, 0.6);
        DialogueResult lowCoverage = DialogueResult.of(caseOf(List.of("incident"), List.of("赔偿金")),
                turns, List.of("incident"), List.of("incident"), 0.3, 0.6);

        assertTrue(ok.passed());
        assertFalse(missingSlot.passed());
        assertEquals(0.5, missingSlot.slotCompletionRate(), 0.0001);
        assertFalse(lowCoverage.passed());
    }

    @Test
    void reportAggregatesPassRateAndCompletion() {
        var turns = List.of(new DialogueResult.DialogueTurnResult("q", "a", 1.0, List.of()));
        DialogueReport report = new DialogueReport("d", List.of(
                DialogueResult.of(caseOf(List.of("a"), List.of()), turns, List.of("a"), List.of("a"), 1.0, 0.6),
                DialogueResult.of(caseOf(List.of("a"), List.of()), turns, List.of("a"), List.of(), 1.0, 0.6)));

        assertEquals(0.5, report.passRate(), 0.0001);
        assertEquals(0.5, report.averageSlotCompletion(), 0.0001);
    }

    private static DialogueCase caseOf(List<String> requiredSlots, List<String> finalKeywords) {
        return new DialogueCase("d1", "a1", List.of(), requiredSlots, finalKeywords);
    }
}
