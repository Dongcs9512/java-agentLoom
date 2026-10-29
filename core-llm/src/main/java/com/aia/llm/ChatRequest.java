package com.aia.llm;

import java.util.List;

public record ChatRequest(List<ChatMessage> messages, List<ToolDefinition> tools, ChatModelConfig config) {}
