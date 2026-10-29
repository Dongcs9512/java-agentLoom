package com.aia.core;

import java.util.Map;

public interface Tool {
    String name();

    String description();

    Map<String, Object> parameters();

    ToolResult execute(ToolInvocation invocation);
}
