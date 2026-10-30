package com.aia.rag.eval;

import java.util.List;
import java.util.Locale;

public record DialogueReport(String name, List<DialogueResult> results) {

    public DialogueReport {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public double passRate() {
        if (results.isEmpty()) {
            return 0.0;
        }
        return (double) results.stream().filter(DialogueResult::passed).count() / results.size();
    }

    public double averageSlotCompletion() {
        if (results.isEmpty()) {
            return 0.0;
        }
        return results.stream().mapToDouble(DialogueResult::slotCompletionRate).average().orElse(0.0);
    }

    public String summary() {
        return String.format(Locale.ROOT, "多轮评测「%s」共 %d 组对话：通过率 %.0f%%，平均槽位完成度 %.0f%%",
                name, results.size(), passRate() * 100, averageSlotCompletion() * 100);
    }
}
