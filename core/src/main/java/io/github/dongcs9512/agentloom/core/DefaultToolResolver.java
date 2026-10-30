package io.github.dongcs9512.agentloom.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 默认工具作用域策略：
 * <ul>
 *   <li>{@code toolNames == null} 或包含 {@code "*"}：暴露全部全局工具</li>
 *   <li>{@code toolNames == []}：不暴露任何全局工具（纯对话 agent）</li>
 *   <li>否则按名单精确匹配，未注册的名字静默忽略</li>
 * </ul>
 * 无论哪种情况，agent 选中技能自带的工具都会并入，同名的只保留一个。
 */
public final class DefaultToolResolver implements ToolResolver {

    @Override
    public List<Tool> resolve(AgentConfig agent, List<Skill> skills, ToolRegistry registry) {
        List<String> toolNames = agent == null ? null : agent.toolNames();
        boolean exposeAll = toolNames == null || toolNames.contains("*");

        List<Tool> resolved = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Tool tool : registry.all()) {
            if (exposeAll || toolNames.contains(tool.name())) {
                if (seen.add(tool.name())) {
                    resolved.add(tool);
                }
            }
        }
        if (skills != null) {
            for (Skill skill : skills) {
                for (Tool tool : skill.tools()) {
                    if (seen.add(tool.name())) {
                        resolved.add(tool);
                    }
                }
            }
        }
        return resolved;
    }
}
