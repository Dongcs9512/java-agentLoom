package com.aia.server;

/**
 * 一次问答的结果。
 *
 * @param answer    助手回答
 * @param sessionId 会话 id（单轮模式下为 null）
 * @param fromCache 是否命中热点问题缓存
 */
public record ChatReply(String answer, String sessionId, boolean fromCache) {
}
