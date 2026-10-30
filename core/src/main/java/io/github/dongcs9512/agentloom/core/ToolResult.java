package io.github.dongcs9512.agentloom.core;

public record ToolResult(String content, boolean isError) {

    public static ToolResult ok(String content) {
        return new ToolResult(content, false);
    }

    public static ToolResult error(String content) {
        return new ToolResult(content, true);
    }
}
