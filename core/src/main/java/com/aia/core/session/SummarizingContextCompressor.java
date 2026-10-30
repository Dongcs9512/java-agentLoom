package com.aia.core.session;

import com.aia.llm.ChatMessage;
import com.aia.llm.ChatModel;
import com.aia.llm.ChatModelConfig;
import com.aia.llm.ChatRequest;

import java.util.List;

/**
 * 超过阈值时调一次模型，把早期对话压成一段摘要，只保留最近若干条消息原文。
 * 摘要失败时自动退化为纯截断，保证对话不因为压缩出错而中断。
 */
public final class SummarizingContextCompressor implements ContextCompressor {

    private static final String SUMMARY_SYSTEM_PROMPT =
            "你是对话摘要助手。把下面的客服对话压缩成不超过 200 字的要点，"
                    + "必须保留：用户诉求、关键事实（时间、地点、涉及方）、已确认的信息。只输出摘要正文。";

    private final ChatModel model;
    private final int thresholdTokens;
    private final int keepRecentMessages;

    public SummarizingContextCompressor(ChatModel model, int thresholdTokens, int keepRecentMessages) {
        this.model = model;
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

        List<ChatMessage> older = messages.subList(0, split);
        List<ChatMessage> recent = List.copyOf(messages.subList(split, messages.size()));
        try {
            return new CompressedContext(summarize(existingSummary, older), recent);
        } catch (RuntimeException e) {
            // 摘要失败不影响主流程，退化为截断
            return new CompressedContext(existingSummary, recent);
        }
    }

    private String summarize(String existingSummary, List<ChatMessage> older) {
        StringBuilder sb = new StringBuilder();
        if (existingSummary != null && !existingSummary.isBlank()) {
            sb.append("【已有摘要】\n").append(existingSummary).append("\n\n");
        }
        sb.append("【新增对话】\n");
        for (ChatMessage message : older) {
            if (message.content() != null && !message.content().isBlank()) {
                sb.append(roleLabel(message)).append(": ").append(message.content()).append('\n');
            }
        }

        ChatRequest request = new ChatRequest(
                List.of(ChatMessage.system(SUMMARY_SYSTEM_PROMPT), ChatMessage.user(sb.toString())),
                List.of(), new ChatModelConfig(null, 400));
        String content = model.chat(request).message().content();
        return content == null || content.isBlank() ? existingSummary : content;
    }

    private static String roleLabel(ChatMessage message) {
        return switch (message.role()) {
            case SYSTEM -> "系统";
            case USER -> "用户";
            case ASSISTANT -> "客服";
            case TOOL -> "工具";
        };
    }
}
