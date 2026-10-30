package com.aia.llm;

import java.util.Set;
import java.util.function.Consumer;

public interface ChatModel {
    ChatResponse chat(ChatRequest request);

    default void stream(ChatRequest request, Consumer<ChatChunk> onChunk) {
        ChatResponse response = chat(request);
        onChunk.accept(new ChatChunk(response.message().content(), response.message().toolCalls(), response.finishReason()));
    }

    Set<ChatModelCapability> capabilities();
}
