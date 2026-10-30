package io.github.dongcs9512.agentloom.core;

import io.github.dongcs9512.agentloom.llm.ToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool tool) {
        if (tools.containsKey(tool.name())) {
            throw new IllegalArgumentException("工具已存在: " + tool.name());
        }
        tools.put(tool.name(), tool);
    }

    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 按注册顺序返回全部工具。
     */
    public List<Tool> all() {
        return List.copyOf(tools.values());
    }

    public List<ToolDefinition> toToolDefinitions() {
        List<ToolDefinition> defs = new ArrayList<>();
        for (Tool tool : tools.values()) {
            defs.add(new ToolDefinition(tool.name(), tool.description(), tool.parameters()));
        }
        return defs;
    }
}
