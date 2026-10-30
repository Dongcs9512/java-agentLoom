package io.github.dongcs9512.agentloom.core.workflow;

import java.util.List;

/**
 * @param answer   本轮给用户的回答（可能是追问，也可能是最终答复）
 * @param stepId   本轮结束时所处的步骤
 * @param finished 流程是否已结束
 * @param trace    本轮走过的步骤，便于排查编排问题
 */
public record WorkflowResult(String answer, String stepId, boolean finished, List<String> trace) {

    public WorkflowResult {
        trace = trace == null ? List.of() : List.copyOf(trace);
    }
}
