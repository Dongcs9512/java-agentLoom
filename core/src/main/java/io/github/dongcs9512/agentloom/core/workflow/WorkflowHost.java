package io.github.dongcs9512.agentloom.core.workflow;

import io.github.dongcs9512.agentloom.core.session.Session;

import java.util.Optional;

/**
 * 工作流引擎需要宿主提供的能力。放在接口里是为了让引擎留在核心库、可单测，
 * 由服务层（AgentRuntime）实现具体调用。
 */
public interface WorkflowHost {

    /** 用指定 agent 跑一轮对话。 */
    String chat(String agentId, Session session, String userMessage);

    /** 按会话当前内容判断该交给哪个专业 agent。 */
    Optional<String> route(Session session);
}
