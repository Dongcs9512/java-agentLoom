package io.github.dongcs9512.agentloom.rag.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 一次多轮对话评测：按脚本逐轮提问，检查每轮回答的关键事实与最终槽位是否收集完整。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DialogueCase(
        String id,
        String agentId,
        List<Turn> turns,
        List<String> requiredSlots,
        List<String> finalExpectedKeywords,
        String expectedScenario) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Turn(String user, List<String> expectKeywords) {
        public Turn {
            expectKeywords = expectKeywords == null ? List.of() : List.copyOf(expectKeywords);
        }
    }

    public DialogueCase {
        turns = turns == null ? List.of() : List.copyOf(turns);
        requiredSlots = requiredSlots == null ? List.of() : List.copyOf(requiredSlots);
        finalExpectedKeywords = finalExpectedKeywords == null ? List.of() : List.copyOf(finalExpectedKeywords);
    }

    public boolean expectsScenario() {
        return expectedScenario != null && !expectedScenario.isBlank();
    }
}
