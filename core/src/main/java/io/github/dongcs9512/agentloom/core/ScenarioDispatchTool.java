package io.github.dongcs9512.agentloom.core;

import io.github.dongcs9512.agentloom.core.obs.MetricsRecorder;
import io.github.dongcs9512.agentloom.core.session.ScenarioRouter;
import io.github.dongcs9512.agentloom.core.session.Session;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 场景分派工具：接待 agent 收集完信息后调用它，由 {@link ScenarioRouter} 确定性地
 * 把案件转给对应专业 agent，而不是让模型自己猜该找谁。
 */
public final class ScenarioDispatchTool extends AbstractTool {

    public static final String NAME = "delegate_to_specialist";

    /** 分派结果记到这个槽位，便于链路追踪与评测断言（不会出现在给模型看的槽位提示里）。 */
    public static final String SCENARIO_SLOT = "__scenario";

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final int MAX_DEPTH = 3;

    private final ScenarioRouter router;
    private final Session session;
    private final Function<String, AgentLoop> loopFactory;
    private final MetricsRecorder metrics;

    public ScenarioDispatchTool(ScenarioRouter router, Session session, Function<String, AgentLoop> loopFactory) {
        this(router, session, loopFactory, MetricsRecorder.noop());
    }

    public ScenarioDispatchTool(ScenarioRouter router, Session session, Function<String, AgentLoop> loopFactory,
                                MetricsRecorder metrics) {
        this.router = router;
        this.session = session;
        this.loopFactory = loopFactory;
        this.metrics = metrics == null ? MetricsRecorder.noop() : metrics;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "把整理好的案件交给对应的专业律师做分析。系统会根据案件内容自动匹配专业方向，无需指定。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("type", "string");
        task.put("description", "案件情况的完整描述，越具体越好（包含时间、地点、诉求）");

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("task", task));
        schema.put("required", List.of("task"));
        return schema;
    }

    @Override
    protected ToolResult doExecute(ToolInvocation invocation) {
        Object rawTask = invocation.arguments().get("task");
        if (rawTask == null || String.valueOf(rawTask).isBlank()) {
            return ToolResult.error("缺少必填参数: task");
        }
        String task = String.valueOf(rawTask);

        Optional<String> agentId = router.route(task, session);
        if (agentId.isEmpty()) {
            return ToolResult.error("暂时判断不出这属于哪类法律问题，请向用户补充确认具体争议类型（辞退、欠薪、加班费、合同、社保、工伤等）");
        }

        int depth = DEPTH.get();
        if (depth >= MAX_DEPTH) {
            return ToolResult.error("转派层级超过上限 " + MAX_DEPTH + "，请直接回答用户");
        }
        DEPTH.set(depth + 1);
        try {
            AgentLoop loop = loopFactory.apply(agentId.get());
            if (loop == null) {
                return ToolResult.error("专业方向 " + agentId.get() + " 尚未配置对应的 agent");
            }
            if (session != null) {
                session.putSlot(SCENARIO_SLOT, agentId.get());
            }
            metrics.count("agentloom.scenario.dispatch", Map.of("agent", agentId.get(), "source", "tool"));
            return ToolResult.ok("【已转交 " + agentId.get() + "】\n" + loop.run(task));
        } finally {
            DEPTH.set(depth);
        }
    }

    public static int currentDepth() {
        return DEPTH.get();
    }
}
