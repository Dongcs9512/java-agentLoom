package io.github.dongcs9512.agentloom.rag.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 一条评测用例。
 *
 * @param expectedDocIds   期望命中的知识文档 id（任一命中即算检索成功）
 * @param expectedKeywords 回答里应当出现的关键事实，用来衡量答案是否落到实处
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalCase(
        String id,
        String question,
        List<String> expectedDocIds,
        List<String> expectedKeywords) {

    public EvalCase {
        expectedDocIds = expectedDocIds == null ? List.of() : List.copyOf(expectedDocIds);
        expectedKeywords = expectedKeywords == null ? List.of() : List.copyOf(expectedKeywords);
    }
}
