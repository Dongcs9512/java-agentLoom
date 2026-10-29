package com.aia.core;

import java.util.Map;

public abstract class AbstractTool implements Tool {

    @Override
    public Map<String, Object> parameters() {
        return Map.of();
    }

    @Override
    public final ToolResult execute(ToolInvocation invocation) {
        try {
            return doExecute(invocation);
        } catch (Exception e) {
            return ToolResult.error(e.getMessage() == null ? "工具执行异常" : e.getMessage());
        }
    }

    protected abstract ToolResult doExecute(ToolInvocation invocation);
}
