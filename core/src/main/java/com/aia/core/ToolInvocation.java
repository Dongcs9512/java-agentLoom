package com.aia.core;

import java.util.Map;

public record ToolInvocation(String toolCallId, String name, Map<String, Object> arguments) {}
