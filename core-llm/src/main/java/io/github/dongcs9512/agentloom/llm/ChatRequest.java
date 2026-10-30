package io.github.dongcs9512.agentloom.llm;

import java.util.List;

public record ChatRequest(List<ChatMessage> messages, List<ToolDefinition> tools, ChatModelConfig config) {}
