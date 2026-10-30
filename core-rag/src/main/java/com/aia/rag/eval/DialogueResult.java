package com.aia.rag.eval;

import java.util.List;
import java.util.Locale;

/**
 * @param missingSlots       最后仍没收集到的槽位
 * @param slotCompletionRate 槽位收集完成度（0~1）
 * @param answerCoverage     整段对话所有回答合起来的关键事实覆盖度
 *                           （不能只看最后一轮：分析往往在中间某轮就给出了，最后一轮可能只是追问）
 */
public record DialogueResult(
        String dialogueId,
        String agentId,
        List<DialogueTurnResult> turns,
        List<String> missingSlots,
        double slotCompletionRate,
        double answerCoverage,
        boolean passed) {

    public record DialogueTurnResult(String user, String answer, double keywordCoverage, List<String> missingKeywords) {
    }

    public static DialogueResult of(DialogueCase evalCase, List<DialogueTurnResult> turns,
                                    List<String> requiredSlots, List<String> filledSlots,
                                    double answerCoverage, double keywordThreshold) {
        List<String> missing = requiredSlots.stream().filter(s -> !filledSlots.contains(s)).toList();
        double completion = requiredSlots.isEmpty()
                ? 1.0
                : (double) (requiredSlots.size() - missing.size()) / requiredSlots.size();
        boolean passed = missing.isEmpty() && answerCoverage >= keywordThreshold;
        return new DialogueResult(evalCase.id(), evalCase.agentId(), turns, missing, completion, answerCoverage, passed);
    }

    public String summary() {
        return String.format(Locale.ROOT, "%s：%d 轮，槽位完成 %.0f%%，答案覆盖 %.0f%%，%s",
                dialogueId, turns.size(), slotCompletionRate * 100, answerCoverage * 100,
                passed ? "通过" : "未通过");
    }
}
