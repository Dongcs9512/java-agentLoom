package io.github.dongcs9512.agentloom.core;

import java.util.Map;

public interface Tool {
    String name();

    String description();

    Map<String, Object> parameters();

    ToolResult execute(ToolInvocation invocation);
}
