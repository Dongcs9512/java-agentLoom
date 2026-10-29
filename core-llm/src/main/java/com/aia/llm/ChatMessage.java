package com.aia.llm;

import java.util.List;

public record ChatMessage(
        ChatMessageRole role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId) {

    public static ChatMessage system(String content) {
        return new ChatMessage(ChatMessageRole.SYSTEM, content, List.of(), null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ChatMessageRole.USER, content, List.of(), null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ChatMessageRole.ASSISTANT, content, List.of(), null);
    }

    public static ChatMessage assistantWithToolCalls(List<ToolCall> calls) {
        return new ChatMessage(ChatMessageRole.ASSISTANT, null, calls, null);
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage(ChatMessageRole.TOOL, content, List.of(), toolCallId);
    }
}
