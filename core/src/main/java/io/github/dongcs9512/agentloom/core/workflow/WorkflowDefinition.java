package io.github.dongcs9512.agentloom.core.workflow;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @param entryAgent 接待 agent，COLLECT 步骤用它收集信息
 * @param params     业务参数，条件里可以用 {@code params.xxx} 引用（比如转人工的金额阈值）
 */
public record WorkflowDefinition(String id, String entryAgent, Map<String, String> params,
                                 List<WorkflowStep> steps) {

    public WorkflowDefinition {
        steps = steps == null ? List.of() : List.copyOf(steps);
        params = params == null ? Map.of() : Map.copyOf(params);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("工作流 " + id + " 没有任何步骤");
        }
    }

    public WorkflowDefinition(String id, String entryAgent, List<WorkflowStep> steps) {
        this(id, entryAgent, Map.of(), steps);
    }

    public Optional<WorkflowStep> first() {
        return steps.isEmpty() ? Optional.empty() : Optional.of(steps.get(0));
    }

    public Optional<WorkflowStep> byId(String stepId) {
        if (stepId == null || stepId.isBlank()) {
            return Optional.empty();
        }
        return steps.stream().filter(s -> stepId.equals(s.id())).findFirst();
    }

    public Optional<WorkflowStep> next(String stepId) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(stepId)) {
                return i + 1 < steps.size() ? Optional.of(steps.get(i + 1)) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** 加载时校验：条件表达式能解析、跳转目标存在。配置写错要在启动时暴露，而不是运行时。 */
    public void validate() {
        for (WorkflowStep step : steps) {
            if (step.type() == StepType.CONDITION) {
                Condition condition = Condition.parse(step.when());
                checkConditionParam(step, condition);
                checkTarget(step, step.then());
                checkTarget(step, step.otherwise());
            }
            if (step.type() == StepType.CALL && (step.agent() == null || step.agent().isBlank())) {
                throw new IllegalArgumentException("工作流 " + id + " 的步骤 " + step.id() + " 缺少 agent");
            }
            if (step.type() == StepType.PARALLEL && step.agents().isEmpty()) {
                throw new IllegalArgumentException("工作流 " + id + " 的步骤 " + step.id() + " 没有配置 agents");
            }
            if (step.type() == StepType.COLLECT && step.slots().isEmpty()) {
                throw new IllegalArgumentException("工作流 " + id + " 的步骤 " + step.id() + " 没有配置槽位");
            }
        }
    }

    /** 条件引用了参数时，参数必须存在；数值比较还要求参数值能当数字用。 */
    private void checkConditionParam(WorkflowStep step, Condition condition) {
        if (!condition.usesParam()) {
            return;
        }
        String value = params.get(condition.paramName());
        if (value == null) {
            throw new IllegalArgumentException("工作流 " + id + " 的步骤 " + step.id()
                    + " 引用了未定义的参数: params." + condition.paramName()
                    + "，请在 params 里补上");
        }
        if (condition.isNumericComparison()) {
            try {
                Double.parseDouble(value.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("工作流 " + id + " 的参数 params."
                        + condition.paramName() + " 必须能当数字用，实际是: " + value);
            }
        }
    }

    private void checkTarget(WorkflowStep step, String target) {
        if (target != null && !target.isBlank() && byId(target).isEmpty()) {
            throw new IllegalArgumentException("工作流 " + id + " 的步骤 " + step.id()
                    + " 跳转目标不存在: " + target);
        }
    }
}
