package io.github.dongcs9512.agentloom.rag.eval;

import io.github.dongcs9512.agentloom.rag.SearchHit;

import java.util.ArrayList;
import java.util.List;

public final class RagEvaluator {

    private RagEvaluator() {
    }

    /**
     * 对单条用例打分：命中哪些期望文档、回答里还缺哪些关键事实。
     */
    public static EvalCaseResult score(EvalCase evalCase, List<SearchHit> hits, String answer) {
        List<String> retrieved = hits == null ? List.of()
                : hits.stream().map(SearchHit::id).toList();
        List<String> hitDocIds = new ArrayList<>();
        for (String expected : evalCase.expectedDocIds()) {
            if (retrieved.stream().anyMatch(id -> id.equals(expected) || id.startsWith(expected + "#"))) {
                hitDocIds.add(expected);
            }
        }

        String text = AnswerNormalizer.normalize(answer);
        List<String> missing = new ArrayList<>();
        for (String keyword : evalCase.expectedKeywords()) {
            if (!text.contains(AnswerNormalizer.normalize(keyword))) {
                missing.add(keyword);
            }
        }
        double coverage = evalCase.expectedKeywords().isEmpty()
                ? 1.0
                : (double) (evalCase.expectedKeywords().size() - missing.size()) / evalCase.expectedKeywords().size();

        return new EvalCaseResult(evalCase.id(), evalCase.question(), !hitDocIds.isEmpty(), hitDocIds,
                coverage, missing, answer);
    }
}
