package io.github.dongcs9512.agentloom.llm;

import java.util.Map;

public record ToolCall(String id, String name, Map<String, Object> arguments) {}
