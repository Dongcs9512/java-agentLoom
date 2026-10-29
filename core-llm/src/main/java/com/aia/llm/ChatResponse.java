package com.aia.llm;

public record ChatResponse(ChatMessage message, String finishReason, Usage usage) {}
