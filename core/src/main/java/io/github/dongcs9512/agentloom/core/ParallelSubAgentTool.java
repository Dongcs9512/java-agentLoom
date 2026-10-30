package io.github.dongcs9512.agentloom.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 并行委派：一次把多个子任务分发给不同 agent 同时执行，再按请求顺序聚合结果。
 *
 * <p>单个子任务失败不会影响其它任务，失败信息会带在对应段落里返回给主 agent。
 */
public final class ParallelSubAgentTool extends AbstractTool {

    public static final String NAME = "parallel_delegate";

    private final Map<String, Supplier<AgentLoop>> loops;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final long timeoutSeconds;

    public ParallelSubAgentTool(Map<String, Supplier<AgentLoop>> loops, ExecutorService executor, long timeoutSeconds) {
        this(loops, executor, false, timeoutSeconds);
    }

    public ParallelSubAgentTool(Map<String, Supplier<AgentLoop>> loops, int poolSize, long timeoutSeconds) {
        this(loops, Executors.newFixedThreadPool(Math.max(1, poolSize)), true, timeoutSeconds);
    }

    private ParallelSubAgentTool(Map<String, Supplier<AgentLoop>> loops, ExecutorService executor,
                                 boolean ownsExecutor, long timeoutSeconds) {
        this.loops = loops == null ? Map.of() : new LinkedHashMap<>(loops);
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "把多个互不依赖的分析任务同时交给不同的专业 agent 处理，"
                + "适合需要从多个角度并行评估的场景（如同时判断劳动法与合同法问题）。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("type", "string");
        agent.put("description", "子 agent 标识，可选值: " + String.join(", ", loops.keySet()));

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("type", "string");
        task.put("description", "交给该子 agent 的任务，信息要完整到它能独立完成");

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", Map.of("agent", agent, "task", task));
        item.put("required", List.of("agent", "task"));

        Map<String, Object> tasks = new LinkedHashMap<>();
        tasks.put("type", "array");
        tasks.put("description", "要并行执行的任务列表，按顺序返回结果");
        tasks.put("items", item);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("tasks", tasks));
        schema.put("required", List.of("tasks"));
        return schema;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected ToolResult doExecute(ToolInvocation invocation) {
        Object rawTasks = invocation.arguments().get("tasks");
        if (!(rawTasks instanceof List<?> tasks) || tasks.isEmpty()) {
            return ToolResult.error("缺少必填参数: tasks（需要至少一个并行任务）");
        }

        List<Future<String>> futures = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Object raw : tasks) {
            if (!(raw instanceof Map<?, ?> task)) {
                return ToolResult.error("tasks 的元素必须是 {agent, task} 对象");
            }
            String agentId = String.valueOf(task.get("agent"));
            String text = String.valueOf(task.get("task"));
            labels.add(agentId);
            Supplier<AgentLoop> loopSupplier = loops.get(agentId);
            if (loopSupplier == null) {
                futures.add(null);
                continue;
            }
            futures.add(executor.submit(() -> loopSupplier.get().run(text)));
        }

        StringBuilder sb = new StringBuilder("并行分析结果：");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
        for (int i = 0; i < futures.size(); i++) {
            sb.append("\n\n【").append(labels.get(i)).append("】");
            Future<String> future = futures.get(i);
            if (future == null) {
                sb.append("未知的子 agent");
                continue;
            }
            try {
                long remaining = Math.max(1, deadline - System.nanoTime());
                sb.append(future.get(remaining, TimeUnit.NANOSECONDS));
            } catch (Exception e) {
                future.cancel(true);
                sb.append("执行失败：").append(e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            }
        }
        return ToolResult.ok(sb.toString());
    }

    public void close() {
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }
}
