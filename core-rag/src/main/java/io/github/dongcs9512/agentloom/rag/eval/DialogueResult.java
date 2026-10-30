package io.github.dongcs9512.agentloom.rag.eval;

import java.util.List;
import java.util.Locale;

/**
 * @param missingSlots       最后仍没收集到的槽位
 * @param slotCompletionRate 槽位收集完成度（0~1）
 * @param answerCoverage     整段对话所有回答合起来的关键事实覆盖度
 *                           （不能只看最后一轮：分析往往在中间某轮就给出了，最后一轮可能只是追问）
 * @param routedScenario     实际分派到的专业 agent，未分派时为 null
 * @param scenarioMatched    是否分派到了期望的专业方向（未声明期望时恒为 true）
 */
public record DialogueResult(
        String dialogueId,
        String agentId,
        List<DialogueTurnResult> turns,
        List<String> missingSlots,
        double slotCompletionRate,
        double answerCoverage,
        String routedScenario,
        boolean scenarioMatched,
        boolean passed) {

    public record DialogueTurnResult(String user, String answer, double keywordCoverage, List<String> missingKeywords) {
    }

    public static DialogueResult of(DialogueCase evalCase, List<DialogueTurnResult> turns,
                                    List<String> requiredSlots, List<String> filledSlots,
                                    double answerCoverage, double keywordThreshold, String routedScenario) {
        List<String> missing = requiredSlots.stream().filter(s -> !filledSlots.contains(s)).toList();
        double completion = requiredSlots.isEmpty()
                ? 1.0
                : (double) (requiredSlots.size() - missing.size()) / requiredSlots.size();
        boolean scenarioMatched = !evalCase.expectsScenario()
                || evalCase.expectedScenario().equals(routedScenario);
        boolean passed = missing.isEmpty() && answerCoverage >= keywordThreshold && scenarioMatched;
        return new DialogueResult(evalCase.id(), evalCase.agentId(), turns, missing, completion, answerCoverage,
                routedScenario, scenarioMatched, passed);
    }

    public String summary() {
        return String.format(Locale.ROOT, "%s：%d 轮，槽位 %.0f%%，覆盖 %.0f%%，分派 %s，%s",
                dialogueId, turns.size(), slotCompletionRate * 100, answerCoverage * 100,
                routedScenario == null ? "未分派" : routedScenario,
                passed ? "通过" : "未通过");
    }
}
