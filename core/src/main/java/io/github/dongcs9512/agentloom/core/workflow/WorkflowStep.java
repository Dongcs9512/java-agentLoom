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
        String otherwise,
        List<String> agents,
        String merge,
        Integer timeoutMs) {

    public WorkflowStep {
        slots = slots == null ? List.of() : List.copyOf(slots);
        agents = agents == null ? List.of() : List.copyOf(agents);
    }

    /** 兼容没有并行字段的旧构造。 */
    public WorkflowStep(String id, StepType type, List<String> slots, Integer maxTurns, String strategy,
                        String agent, String when, String then, String otherwise) {
        this(id, type, slots, maxTurns, strategy, agent, when, then, otherwise, List.of(), null, null);
    }

    public int timeoutMillisOrDefault() {
        return timeoutMs == null || timeoutMs <= 0 ? 30_000 : timeoutMs;
    }

    /** concat（拼接）或 synthesize（再调一次模型合成），默认 concat。 */
    public String mergeOrDefault() {
        return merge == null || merge.isBlank() ? "concat" : merge.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public int maxTurnsOrDefault() {
        return maxTurns == null || maxTurns <= 0 ? 5 : maxTurns;
    }

    /** CALL 步骤里代表"用分派结果那个 agent"。 */
    public boolean usesRoutedAgent() {
        return agent != null && agent.trim().equals("@routed");
    }
}
