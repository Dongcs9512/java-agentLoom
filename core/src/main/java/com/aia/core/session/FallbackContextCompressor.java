package com.aia.core.session;

import com.aia.llm.ChatMessage;

import java.util.List;

/**
 * 组合压缩：先用主策略，主策略抛异常时退回兜底策略。
 * 典型用法：模型语义压缩 + 纯截断兜底。
 */
public final class FallbackContextCompressor implements ContextCompressor {

    private final ContextCompressor primary;
    private final ContextCompressor fallback;

    public FallbackContextCompressor(ContextCompressor primary, ContextCompressor fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public CompressedContext compress(String existingSummary, List<ChatMessage> messages) {
        try {
            return primary.compress(existingSummary, messages);
        } catch (RuntimeException e) {
            return fallback.compress(existingSummary, messages);
        }
    }
}
