package io.github.dongcs9512.agentloom.rag.eval;

import java.util.List;

/**
 * @param passed     是否达标
 * @param violations 未达标的指标说明，达标时为空
 */
public record GateResult(boolean passed, List<String> violations) {

    public GateResult {
        violations = violations == null ? List.of() : List.copyOf(violations);
    }
}
