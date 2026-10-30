package io.github.dongcs9512.agentloom.core.workflow;

import java.util.List;

/**
 * 工作流的一步。不同 type 只用其中一部分字段，其余留空。
 */
public record WorkflowStep(
        String id,
        StepType type,
        List<String> slots,
        Integer maxTurns,
        String strategy,
        String agent,
        String when,
        String then,
        String otherwise) {

    public WorkflowStep {
        slots = slots == null ? List.of() : List.copyOf(slots);
    }

    public int maxTurnsOrDefault() {
        return maxTurns == null || maxTurns <= 0 ? 5 : maxTurns;
    }

    /** CALL 步骤里代表"用分派结果那个 agent"。 */
    public boolean usesRoutedAgent() {
        return agent != null && agent.trim().equals("@routed");
    }
}
