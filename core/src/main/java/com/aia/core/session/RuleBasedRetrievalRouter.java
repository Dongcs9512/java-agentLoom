package com.aia.core.session;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 默认路由：短句寒暄（你好/在吗/谢谢…）走固定话术或纯对话，
 * 其余一律走知识库检索。规则简单可预测，避免为了判断意图多调一次模型。
 */
public final class RuleBasedRetrievalRouter implements RetrievalRouter {

    private static final Set<String> GREETINGS = Set.of(
            "你好", "您好", "hi", "hello", "hey", "在吗", "在不在", "在么", "有人吗", "哈喽", "嗨");

    /** 超过这个长度就不再当寒暄处理，避免把真实问题误判成打招呼 */
    private static final int CHITCHAT_MAX_LENGTH = 12;

    private final String chitchatReply;
    private final List<String> extraChitchat;
    private final ChitchatMode mode;

    public RuleBasedRetrievalRouter(String chitchatReply, List<String> extraChitchat) {
        this(chitchatReply, extraChitchat, ChitchatMode.CANNED);
    }

    public RuleBasedRetrievalRouter(String chitchatReply, List<String> extraChitchat, ChitchatMode mode) {
        this.chitchatReply = chitchatReply;
        this.extraChitchat = extraChitchat == null ? List.of() : List.copyOf(extraChitchat);
        this.mode = mode == null ? ChitchatMode.CANNED : mode;
    }

    @Override
    public RouteResult route(String userMessage, Session session) {
        String text = userMessage == null ? "" : userMessage.trim();
        if (text.isEmpty()) {
            return chitchatResult();
        }
        if (isChitchat(text)) {
            return chitchatResult();
        }
        return RouteResult.retrieve();
    }

    private RouteResult chitchatResult() {
        return mode == ChitchatMode.MODEL ? RouteResult.direct() : RouteResult.canned(chitchatReply);
    }

    private boolean isChitchat(String text) {
        if (text.length() > CHITCHAT_MAX_LENGTH) {
            return false;
        }
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return true;
        }
        if (GREETINGS.contains(normalized)) {
            return true;
        }
        return extraChitchat.stream().anyMatch(phrase -> normalized.equals(normalize(phrase)));
    }

    private static String normalize(String text) {
        return text.replaceAll("[\\s，。！？!?,.~、：:；;]", "").toLowerCase(Locale.ROOT);
    }
}
