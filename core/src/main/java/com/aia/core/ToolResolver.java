package com.aia.core;

import java.util.List;

/**
 * 决定某个 agent 能看到哪些工具。默认实现见 {@link DefaultToolResolver}。
 * 需要按租户、角色或数据库动态分配工具时，实现本接口并在构造 AgentRuntime 时注入即可。
 */
public interface ToolResolver {

    /**
     * @param agent    当前 agent 配置（可能为 null，表示未配置）
     * @param skills   agent 选中的技能
     * @param registry 全局工具注册表
     * @return 该 agent 可用的工具，顺序即暴露给模型的顺序
     */
    List<Tool> resolve(AgentConfig agent, List<Skill> skills, ToolRegistry registry);
}
