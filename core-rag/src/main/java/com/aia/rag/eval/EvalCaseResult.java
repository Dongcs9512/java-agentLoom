package com.aia.rag.eval;

import java.util.List;

/**
 * @param retrievalHit     期望的文档是否被检索到
 * @param hitDocIds        实际命中的期望文档
 * @param keywordCoverage  回答覆盖了多少比例的关键事实（0~1）
 * @param missingKeywords  回答里缺失的关键事实
 */
public record EvalCaseResult(
        String caseId,
        String question,
        boolean retrievalHit,
        List<String> hitDocIds,
        double keywordCoverage,
        List<String> missingKeywords,
        String answer) {
}
