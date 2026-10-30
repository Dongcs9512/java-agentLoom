package io.github.dongcs9512.agentloom.core;

import io.github.dongcs9512.agentloom.llm.ChatMessage;
import io.github.dongcs9512.agentloom.llm.ChatModel;
import io.github.dongcs9512.agentloom.llm.ChatModelConfig;
import io.github.dongcs9512.agentloom.llm.ChatRequest;
import io.github.dongcs9512.agentloom.llm.ChatResponse;
import io.github.dongcs9512.agentloom.llm.ToolCall;
import io.github.dongcs9512.agentloom.llm.ToolDefinition;
import io.github.dongcs9512.agentloom.llm.Usage;
import io.github.dongcs9512.agentloom.core.obs.MetricsRecorder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public final class AgentLoop {

    private static final int DEFAULT_MAX_ITERATIONS = 10;

    private final ChatModel model;
    private final List<Skill> skills;
    private final String systemPrompt;
    private final int maxIterations;
    /** 该 agent 可见并可执行的工具，已按作用域解析并去重。 */
    private final Map<String, Tool> tools;
    private final MetricsRecorder metrics;
    private final String modelName;

    public AgentLoop(ChatModel model, ToolRegistry registry, String systemPrompt) {
        this(model, registry.all(), List.of(), systemPrompt, DEFAULT_MAX_ITERATIONS);
    }

    public AgentLoop(ChatModel model, ToolRegistry registry, String systemPrompt, int maxIterations) {
        this(model, registry.all(), List.of(), systemPrompt, maxIterations);
    }

    public AgentLoop(ChatModel model, ToolRegistry registry, List<Skill> skills, String systemPrompt) {
        this(model, registry.all(), skills, systemPrompt, DEFAULT_MAX_ITERATIONS);
    }

    public AgentLoop(ChatModel model, ToolRegistry registry, List<Skill> skills, String systemPrompt, int maxIterations) {
        this(model, registry.all(), skills, systemPrompt, maxIterations);
    }

    public AgentLoop(ChatModel model, List<Tool> tools, String systemPrompt) {
        this(model, tools, List.of(), systemPrompt, DEFAULT_MAX_ITERATIONS);
    }

    /**
     * @param tools 该 agent 可见的工具集，通常由 {@link ToolResolver} 解析后传入；
     *              技能自带的工具会自动并入，同名的只保留一个
     */
    public AgentLoop(ChatModel model, List<Tool> tools, List<Skill> skills, String systemPrompt) {
        this(model, tools, skills, systemPrompt, DEFAULT_MAX_ITERATIONS);
    }

    public AgentLoop(ChatModel model, List<Tool> tools, List<Skill> skills, String systemPrompt, int maxIterations) {
        this(model, tools, skills, systemPrompt, maxIterations, MetricsRecorder.noop(), "unknown");
    }

    public AgentLoop(ChatModel model, List<Tool> tools, List<Skill> skills, String systemPrompt,
                     MetricsRecorder metrics, String modelName) {
        this(model, tools, skills, systemPrompt, DEFAULT_MAX_ITERATIONS, metrics, modelName);
    }

    /**
     * @param metrics   指标记录器，用于统计模型调用次数与 token 消耗
     * @param modelName 模型名，作为指标标签
     */
    public AgentLoop(ChatModel model, List<Tool> tools, List<Skill> skills, String systemPrompt, int maxIterations,
                     MetricsRecorder metrics, String modelName) {
        this.metrics = metrics == null ? MetricsRecorder.noop() : metrics;
        this.modelName = modelName == null || modelName.isBlank() ? "unknown" : modelName;
        this.model = model;
        this.skills = skills == null ? List.of() : List.copyOf(skills);
        this.systemPrompt = systemPrompt;
        this.maxIterations = maxIterations;
        this.tools = mergeTools(tools, this.skills);
    }

    /** 记录一次模型调用的次数与 token 消耗。 */
    private void recordUsage(Usage usage, boolean stream) {
        metrics.count("agentloom.llm.calls", Map.of("model", modelName, "stream", String.valueOf(stream)));
        if (usage == null) {
            return;
        }
        if (usage.promptTokens() != null) {
            metrics.count("agentloom.llm.tokens", usage.promptTokens(), Map.of("model", modelName, "type", "prompt"));
        }
        if (usage.completionTokens() != null) {
            metrics.count("agentloom.llm.tokens", usage.completionTokens(), Map.of("model", modelName, "type", "completion"));
        }
    }

    public String run(String userMessage) {
        return run(List.of(ChatMessage.system(effectiveSystemPrompt()), ChatMessage.user(userMessage)));
    }

    /**
     * 用一段完整的对话（含 system 消息）跑一轮，多轮会话场景下由调用方拼好历史再传进来。
     */
    public String run(List<ChatMessage> conversation) {
        List<ChatMessage> messages = new ArrayList<>(conversation);

        for (int i = 0; i < maxIterations; i++) {
            ChatRequest request = new ChatRequest(List.copyOf(messages), allToolDefinitions(),
                    new ChatModelConfig(null, null));
            ChatResponse response = model.chat(request);
            recordUsage(response.usage(), false);
            ChatMessage message = response.message();

            if (message.toolCalls().isEmpty()) {
                return message.content() == null ? "" : message.content();
            }

            messages.add(message);
            for (ToolCall call : message.toolCalls()) {
                ToolResult result = executeTool(call);
                messages.add(ChatMessage.tool(call.id(), result.content()));
            }
        }
        throw new IllegalStateException("AgentLoop 达到最大迭代次数: " + maxIterations);
    }

    public void stream(String userMessage, Consumer<String> onDelta) {
        stream(List.of(ChatMessage.system(effectiveSystemPrompt()), ChatMessage.user(userMessage)), onDelta);
    }

    public void stream(List<ChatMessage> conversation, Consumer<String> onDelta) {
        List<ChatMessage> messages = new ArrayList<>(conversation);

        for (int i = 0; i < maxIterations; i++) {
            List<ToolCall> toolCalls = new ArrayList<>();
            metrics.count("agentloom.llm.calls", Map.of("model", modelName, "stream", "true"));
            model.stream(new ChatRequest(List.copyOf(messages), allToolDefinitions(), new ChatModelConfig(null, null)), chunk -> {
                if (chunk.content() != null) {
                    onDelta.accept(chunk.content());
                }
                if (chunk.toolCalls() != null) {
                    toolCalls.addAll(chunk.toolCalls());
                }
            });
            if (toolCalls.isEmpty()) {
                return;
            }
            messages.add(ChatMessage.assistantWithToolCalls(toolCalls));
            for (ToolCall call : toolCalls) {
                ToolResult result = executeTool(call);
                messages.add(ChatMessage.tool(call.id(), result.content()));
            }
        }
        throw new IllegalStateException("AgentLoop 达到最大迭代次数: " + maxIterations);
    }

    /**
     * 该 agent 当前可见的工具名，便于排查作用域配置是否符合预期。
     */
    public List<String> visibleToolNames() {
        return List.copyOf(tools.keySet());
    }

    private static Map<String, Tool> mergeTools(List<Tool> tools, List<Skill> skills) {
        Map<String, Tool> merged = new LinkedHashMap<>();
        if (tools != null) {
            for (Tool tool : tools) {
                merged.putIfAbsent(tool.name(), tool);
            }
        }
        for (Skill skill : skills) {
            for (Tool tool : skill.tools()) {
                merged.putIfAbsent(tool.name(), tool);
            }
        }
        return merged;
    }

    /**
     * 系统提示词，已并入技能说明。使用 {@link #run(List)} 自己拼历史时，应把它作为首条 system 消息。
     */
    public String effectiveSystemPrompt() {
        if (skills.isEmpty()) {
            return systemPrompt;
        }
        StringBuilder sb = new StringBuilder(systemPrompt);
        sb.append("\n\n可用技能:\n");
        for (Skill skill : skills) {
            sb.append("## 技能: ").append(skill.name()).append("\n");
            sb.append(skill.description()).append("\n");
            sb.append(skill.instructions()).append("\n\n");
        }
        return sb.toString();
    }

    private List<ToolDefinition> allToolDefinitions() {
        List<ToolDefinition> defs = new ArrayList<>();
        for (Tool tool : tools.values()) {
            defs.add(new ToolDefinition(tool.name(), tool.description(), tool.parameters()));
        }
        return defs;
    }

    private ToolResult executeTool(ToolCall call) {
        Optional<Tool> tool = Optional.ofNullable(tools.get(call.name()));
        if (tool.isEmpty()) {
            return ToolResult.error("未找到工具: " + call.name());
        }
        try {
            return tool.get().execute(new ToolInvocation(call.id(), call.name(), call.arguments()));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
