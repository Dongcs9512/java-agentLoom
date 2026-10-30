package com.aia.rag.eval;

import java.util.ArrayList;
import java.util.List;

public final class DialogueEvaluator {

    private DialogueEvaluator() {
    }

    /**
     * 单轮打分：回答覆盖了哪些期望的关键事实。
     */
    public static DialogueResult.DialogueTurnResult scoreTurn(String user, String answer, List<String> expectedKeywords) {
        String normalized = AnswerNormalizer.normalize(answer);
        List<String> missing = new ArrayList<>();
        for (String keyword : expectedKeywords) {
            if (!normalized.contains(AnswerNormalizer.normalize(keyword))) {
                missing.add(keyword);
            }
        }
        double coverage = expectedKeywords.isEmpty()
                ? 1.0
                : (double) (expectedKeywords.size() - missing.size()) / expectedKeywords.size();
        return new DialogueResult.DialogueTurnResult(user, answer, coverage, missing);
    }
}
