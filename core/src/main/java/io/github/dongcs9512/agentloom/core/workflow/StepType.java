package io.github.dongcs9512.agentloom.core.workflow;

public enum StepType {
    /** 收集信息：跑接待 agent，直到指定槽位收集齐或轮次上限 */
    COLLECT,
    /** 分派：按会话内容判断该交给哪个专业 agent（不做模型调用） */
    DISPATCH,
    /** 调用：跑指定 agent（或分派结果），产出最终回答 */
    CALL,
    /** 条件分支：按槽位取值决定跳到哪个步骤 */
    CONDITION
}
