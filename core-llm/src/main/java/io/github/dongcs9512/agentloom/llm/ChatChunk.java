package io.github.dongcs9512.agentloom.llm;

import java.util.List;

public record ChatChunk(String content, List<ToolCall> toolCalls, String finishReason) {

    public ChatChunk(String content) {
        this(content, List.of(), null);
    }
}
