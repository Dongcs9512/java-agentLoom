package com.aia.core;

import com.aia.llm.ChatMessage;
import com.aia.llm.ChatModel;
import com.aia.llm.ChatModelConfig;
import com.aia.llm.ChatRequest;
import com.aia.llm.ChatResponse;
import com.aia.llm.ToolCall;
import com.aia.llm.ToolDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class AgentLoop {

    private static final int DEFAULT_MAX_ITERATIONS = 10;

    private final ChatModel model;
    private final ToolRegistry tools;
    private final List<Skill> skills;
    private final String systemPrompt;
    private final int maxIterations;

    public AgentLoop(ChatModel model, ToolRegistry tools, String systemPrompt) {
        this(model, tools, List.of(), systemPrompt, DEFAULT_MAX_ITERATIONS);
    }

    public AgentLoop(ChatModel model, ToolRegistry tools, String systemPrompt, int maxIterations) {
        this(model, tools, List.of(), systemPrompt, maxIterations);
    }

    public AgentLoop(ChatModel model, ToolRegistry tools, List<Skill> skills, String systemPrompt) {
        this(model, tools, skills, systemPrompt, DEFAULT_MAX_ITERATIONS);
    }

    public AgentLoop(ChatModel model, ToolRegistry tools, List<Skill> skills, String systemPrompt, int maxIterations) {
        this.model = model;
        this.tools = tools;
        this.skills = skills == null ? List.of() : List.copyOf(skills);
        this.systemPrompt = systemPrompt;
        this.maxIterations = maxIterations;
    }

    public String run(String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(effectiveSystemPrompt()));
        messages.add(ChatMessage.user(userMessage));

        for (int i = 0; i < maxIterations; i++) {
            ChatRequest request = new ChatRequest(List.copyOf(messages), allToolDefinitions(),
                    new ChatModelConfig(null, null));
            ChatResponse response = model.chat(request);
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

    private String effectiveSystemPrompt() {
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
        List<ToolDefinition> defs = new ArrayList<>(tools.toToolDefinitions());
        Set<String> seen = new HashSet<>();
        for (ToolDefinition def : defs) {
            seen.add(def.name());
        }
        for (Skill skill : skills) {
            for (Tool tool : skill.tools()) {
                if (seen.add(tool.name())) {
                    defs.add(new ToolDefinition(tool.name(), tool.description(), tool.parameters()));
                }
            }
        }
        return defs;
    }

    private ToolResult executeTool(ToolCall call) {
        Optional<Tool> tool = tools.get(call.name());
        if (tool.isEmpty()) {
            for (Skill skill : skills) {
                tool = skill.tools().stream().filter(t -> t.name().equals(call.name())).findFirst();
                if (tool.isPresent()) {
                    break;
                }
            }
        }
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
