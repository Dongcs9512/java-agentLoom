package io.github.dongcs9512.agentloom.core;

import java.util.Map;

public record ToolInvocation(String toolCallId, String name, Map<String, Object> arguments) {}
