package io.github.dongcs9512.agentloom.llm;

public record ChatResponse(ChatMessage message, String finishReason, Usage usage) {}
