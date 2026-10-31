package io.github.dongcs9512.agentloom.server;

/**
 * 一次问答的结果。
 *
 * @param answer    助手回答；转人工期间为 null
 * @param sessionId 会话 id（单轮模式下为 null）
 * @param fromCache 是否命中热点问题缓存
 * @param status    ok / waiting_human
 */
public record ChatReply(String answer, String sessionId, boolean fromCache, String status) {

    public ChatReply(String answer, String sessionId, boolean fromCache) {
        this(answer, sessionId, fromCache, "ok");
    }

    /** 已转人工：AI 不再回复，用户消息只入库等坐席处理。 */
    public static ChatReply waitingHuman(String sessionId) {
        return new ChatReply(null, sessionId, false, "waiting_human");
    }
}
