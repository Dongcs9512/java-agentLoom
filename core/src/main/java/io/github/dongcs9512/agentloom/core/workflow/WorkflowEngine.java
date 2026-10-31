package io.github.dongcs9512.agentloom.core.workflow;

import io.github.dongcs9512.agentloom.core.ScenarioDispatchTool;
import io.github.dongcs9512.agentloom.core.session.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工作流引擎：按定义逐步推进，状态存在会话里，因此能跨多轮用户消息。
 *
 * <p>一次 {@link #step} 调用会尽可能往前走，直到需要用户补充信息（COLLECT 未满足）、
 * 或产出最终回答（CALL）、或流程结束。
 */
public final class WorkflowEngine {

    /** 当前步骤 id，存在会话槽位里，前缀 __ 表示不展示给模型。 */
    public static final String SLOT_STEP = "__wf_step";
    /** 当前 COLLECT 步骤已经用掉的轮次。 */
    public static final String SLOT_TURNS = "__wf_turns";
    /** 分派结果。 */
    public static final String SLOT_ROUTED = "__wf_routed";
    /** 流程已结束且该继续由某个专业 agent 接待（sticky），后续消息不再重走流程。 */
    public static final String SLOT_DONE = "__wf_done";

    /** 单轮最多推进多少步，防止条件写错导致循环跳转。 */
    private static final int MAX_STEPS_PER_TURN = 20;

    public WorkflowResult step(WorkflowDefinition definition, Session session, String userMessage,
                               WorkflowHost host) {
        String stepId = session.slot(SLOT_STEP);
        if (stepId.isBlank()) {
            stepId = definition.first()
                    .orElseThrow(() -> new IllegalStateException("工作流没有步骤: " + definition.id()))
                    .id();
        }
        int turns = parseInt(session.slot(SLOT_TURNS));
        List<String> trace = new ArrayList<>();
        String pendingMessage = userMessage;

        for (int guard = 0; guard < MAX_STEPS_PER_TURN; guard++) {
            WorkflowStep step = definition.byId(stepId).orElse(null);
            if (step == null) {
                reset(session);
                return new WorkflowResult("", stepId, true, trace);
            }

            switch (step.type()) {
                case COLLECT -> {
                    String answer = host.chat(definition.entryAgent(), session, pendingMessage);
                    trace.add("collect:" + step.id());
                    pendingMessage = null;
                    turns++;

                    if (slotsFilled(step.slots(), session)) {
                        trace.add("collect:done");
                        stepId = advance(definition, step.id());
                        turns = 0;
                        continue;
                    }
                    if (turns >= step.maxTurnsOrDefault()) {
                        trace.add("collect:max-turns");
                        stepId = advance(definition, step.id());
                        turns = 0;
                        continue;
                    }
                    session.putSlot(SLOT_STEP, step.id());
                    session.putSlot(SLOT_TURNS, String.valueOf(turns));
                    return new WorkflowResult(answer, step.id(), false, trace);
                }
                case DISPATCH -> {
                    Optional<String> routed = host.route(session);
                    trace.add("dispatch:" + routed.orElse("none"));
                    if (routed.isEmpty()) {
                        session.putSlot(SLOT_STEP, step.id());
                        return new WorkflowResult("抱歉，我还没判断出这属于哪类问题，能再具体说说吗？",
                                step.id(), false, trace);
                    }
                    session.putSlot(SLOT_ROUTED, routed.get());
                    // 与内置分派共用同一个槽位，评测和排查脚本都能读到
                    session.putSlot(ScenarioDispatchTool.SCENARIO_SLOT, routed.get());
                    stepId = advance(definition, step.id());
                    turns = 0;
                    continue;
                }
                case CALL -> {
                    String target = step.usesRoutedAgent() ? session.slot(SLOT_ROUTED) : step.agent();
                    if (target == null || target.isBlank()) {
                        reset(session);
                        return new WorkflowResult("流程配置有误：没有可调用的 agent", step.id(), true, trace);
                    }
                    String task = pendingMessage == null ? buildTask(session) : pendingMessage;
                    String answer = host.chat(target, session, task);
                    trace.add("call:" + target);
                    reset(session);
                    // 交给专业 agent 作答的流程，后续消息继续由它接待；
                    // 转人工之类的固定 agent 不 sticky，免得用户追问一直收到同一句话
                    session.putSlot(SLOT_DONE, step.usesRoutedAgent() ? "true" : "false");
                    if (step.usesRoutedAgent()) {
                        session.putSlot(SLOT_ROUTED, target);
                    }
                    return new WorkflowResult(answer, step.id(), true, trace);
                }
                case CONDITION -> {
                    boolean matched = Condition.parse(step.when()).evaluate(session.slots(), definition.params());
                    String target = matched ? step.then() : step.otherwise();
                    trace.add("condition:" + (matched ? "then" : "otherwise") + "->" + target);
                    if (target == null || target.isBlank()) {
                        reset(session);
                        return new WorkflowResult("", step.id(), true, trace);
                    }
                    stepId = target;
                }
            }
        }
        throw new IllegalStateException("工作流 " + definition.id()
                + " 单轮推进步骤过多，请检查条件步骤是否形成循环跳转");
    }

    /** 流程结束后清空状态，下一条用户消息重新从头开始。 */
    private void reset(Session session) {
        session.putSlot(SLOT_STEP, "");
        session.putSlot(SLOT_TURNS, "0");
    }

    private String advance(WorkflowDefinition definition, String currentStepId) {
        return definition.next(currentStepId).map(WorkflowStep::id).orElse("");
    }

    private boolean slotsFilled(List<String> slots, Session session) {
        for (String slot : slots) {
            if (session.slot(slot).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String buildTask(Session session) {
        StringBuilder sb = new StringBuilder("请根据以下情况给出分析：\n");
        for (Map.Entry<String, Object> entry : session.slots().entrySet()) {
            if (!entry.getKey().startsWith("__")) {
                sb.append("- ").append(entry.getKey()).append('：').append(entry.getValue()).append('\n');
            }
        }
        return sb.toString();
    }

    private static int parseInt(String raw) {
        try {
            return raw == null || raw.isBlank() ? 0 : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
