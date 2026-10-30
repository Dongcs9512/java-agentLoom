package io.github.dongcs9512.agentloom.llm;

import java.util.Map;

public record ToolDefinition(String name, String description, Map<String, Object> parameters) {}
