package com.aia.core.session;

import com.aia.llm.ChatMessage;

import java.util.List;

/**
 * 纯截断：超阈值时只保留最近若干条消息，早期内容直接丢弃。
 * 零额外成本，适合对成本敏感或历史信息价值不高的场景。
 */
public final class TruncatingContextCompressor implements ContextCompressor {

    private final int thresholdTokens;
    private final int keepRecentMessages;

    public TruncatingContextCompressor(int thresholdTokens, int keepRecentMessages) {
        this.thresholdTokens = thresholdTokens;
        this.keepRecentMessages = Math.max(0, keepRecentMessages);
    }

    @Override
    public CompressedContext compress(String existingSummary, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return new CompressedContext(existingSummary, messages);
        }
        int total = TokenEstimator.estimate(messages) + TokenEstimator.estimate(existingSummary);
        int split = Math.max(0, messages.size() - keepRecentMessages);
        if (total <= thresholdTokens || split == 0) {
            return new CompressedContext(existingSummary, messages);
        }
        return new CompressedContext(existingSummary, List.copyOf(messages.subList(split, messages.size())));
    }
}
