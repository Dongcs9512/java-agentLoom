package com.aia.llm;

import java.util.Set;

public interface ChatModel {
    ChatResponse chat(ChatRequest request);

    Set<ChatModelCapability> capabilities();
}
