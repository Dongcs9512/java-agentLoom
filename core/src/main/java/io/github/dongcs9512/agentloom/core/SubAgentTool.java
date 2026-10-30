package io.github.dongcs9512.agentloom.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 把另一个 agent 包装成工具，实现子 agent 委派：主 agent 在 tool-calling 循环里
 * 调用 {@code delegate_to_<agentId>}，子 agent 独立跑一轮并把它最终的回答作为工具结果返回。
 *
 * <p>因为子 agent 本身也是一次完整的 {@link AgentLoop}，所以天然支持多级嵌套；
 * 委派深度用 ThreadLocal 计数，超过 {@code maxDepth} 直接返回错误，避免互相委派导致无限递归。
 */
public final class SubAgentTool extends AbstractTool {

    public static final int DEFAULT_MAX_DEPTH = 3;

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private final String name;
    private final String description;
    private final Supplier<AgentLoop> loopSupplier;
    private final int maxDepth;

    public SubAgentTool(String name, String description, Supplier<AgentLoop> loopSupplier) {
        this(name, description, loopSupplier, DEFAULT_MAX_DEPTH);
    }

    public SubAgentTool(String name, String description, Supplier<AgentLoop> loopSupplier, int maxDepth) {
        this.name = name;
        this.description = description;
        this.loopSupplier = loopSupplier;
        this.maxDepth = maxDepth;
    }

    /**
     * @param agentId     子 agent 的 id，工具名会是 {@code delegate_to_<agentId>}
     * @param description 告诉主 agent 什么时候该把任务交给它
     */
    public static SubAgentTool of(String agentId, String description, Supplier<AgentLoop> loopSupplier) {
        return new SubAgentTool("delegate_to_" + agentId, description, loopSupplier);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("type", "string");
        task.put("description", "要交给子 agent 完成的任务，描述得越完整越好");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("task", task);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("task"));
        return schema;
    }

    @Override
    protected ToolResult doExecute(ToolInvocation invocation) {
        Object rawTask = invocation.arguments().get("task");
        if (rawTask == null || String.valueOf(rawTask).isBlank()) {
            return ToolResult.error("缺少必填参数: task");
        }
        int depth = DEPTH.get();
        if (depth >= maxDepth) {
            return ToolResult.error("子 agent 委派层级超过上限 " + maxDepth + "，请直接回答用户");
        }
        DEPTH.set(depth + 1);
        try {
            AgentLoop loop = loopSupplier.get();
            return ToolResult.ok(loop.run(String.valueOf(rawTask)));
        } finally {
            DEPTH.set(depth);
        }
    }

    /**
     * 当前线程的委派深度，便于日志与排查。
     */
    public static int currentDepth() {
        return DEPTH.get();
    }
}
