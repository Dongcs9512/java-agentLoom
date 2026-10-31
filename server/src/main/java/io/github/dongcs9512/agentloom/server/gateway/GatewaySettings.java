package io.github.dongcs9512.agentloom.server.gateway;

/**
 * @param requireOperator 为 true 时，解析不出操作者身份的请求会被拒绝（401）
 */
public record GatewaySettings(boolean requireOperator) {
}
