package io.github.dongcs9512.agentloom.core.session;

import io.github.dongcs9512.agentloom.llm.ChatMessage;
import io.github.dongcs9512.agentloom.llm.ChatModel;
import io.github.dongcs9512.agentloom.llm.ChatModelConfig;
import io.github.dongcs9512.agentloom.llm.ChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 让模型自己决定压什么：一次调用同时产出摘要和「必须逐字保留的消息下标」，
 * 其余早期消息被摘要替换。模型输出不合法时退化为纯截断。
 */
public final class ModelSelectiveContextCompressor implements ContextCompressor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM_PROMPT =
            "你是对话压缩器。阅读客服对话后完成两件事，并严格只输出一个 JSON 对象：\n"
                    + "1. summary：不超过 200 字的摘要，保留用户诉求、关键事实（时间、地点、涉及方）、已确认信息；\n"
                    + "2. keep：一个数组，列出必须逐字保留的消息下标（例如 [0,3]），用于保留关键原话；没有就给 []。\n"
                    + "格式示例：{\"summary\":\"用户咨询被辞退的赔偿问题\",\"keep\":[0,2]}";

    private final ChatModel model;
    private final int thresholdTokens;
    private final int keepRecentMessages;

    public ModelSelectiveContextCompressor(ChatModel model, int thresholdTokens, int keepRecentMessages) {
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
            String reply = askModel(existingSummary, older);
            JsonNode root = MAPPER.readTree(extractJson(reply));
            String summary = root.path("summary").asText(existingSummary);
            Set<Integer> keepIndices = new LinkedHashSet<>();
            for (JsonNode index : root.path("keep")) {
                int i = index.asInt(-1);
                if (i >= 0 && i < older.size()) {
                    keepIndices.add(i);
                }
            }
            List<ChatMessage> kept = new ArrayList<>();
            for (int i : keepIndices) {
                kept.add(older.get(i));
            }
            kept.addAll(recent);
            return new CompressedContext(summary, kept);
        } catch (Exception e) {
            // 模型不可用或输出不合法：退化为截断，保证对话继续
            return new CompressedContext(existingSummary, recent);
        }
    }

    private String askModel(String existingSummary, List<ChatMessage> older) {
        StringBuilder sb = new StringBuilder();
        if (existingSummary != null && !existingSummary.isBlank()) {
            sb.append("【已有摘要】\n").append(existingSummary).append("\n\n");
        }
        sb.append("【对话】\n");
        for (int i = 0; i < older.size(); i++) {
            ChatMessage message = older.get(i);
            if (message.content() != null && !message.content().isBlank()) {
                sb.append('#').append(i).append(' ').append(message.role()).append(": ")
                        .append(message.content()).append('\n');
            }
        }
        ChatRequest request = new ChatRequest(
                List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(sb.toString())),
                List.of(), new ChatModelConfig(null, 500));
        String content = model.chat(request).message().content();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("模型返回空摘要");
        }
        return content;
    }

    /**
     * 模型有时会把 JSON 包在 ```json 代码块里，这里剥掉再解析。
     */
    private static String extractJson(String reply) {
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("模型输出不含 JSON: " + reply);
        }
        return reply.substring(start, end + 1);
    }
}
