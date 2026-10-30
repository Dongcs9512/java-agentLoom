package io.github.dongcs9512.agentloom.core.session;

public enum RouteDecision {
    /** 直接用固定话术回复，不调模型 */
    CANNED,
    /** 调模型但不下挂知识库工具 */
    DIRECT,
    /** 正常走知识库检索 */
    RETRIEVE
}
