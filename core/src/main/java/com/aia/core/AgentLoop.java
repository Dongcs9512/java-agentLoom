package com.aia.core;

import com.aia.llm.ChatMessage;
import com.aia.llm.ChatModel;
import com.aia.llm.ChatModelConfig;
import com.aia.llm.ChatRequest;
import com.aia.llm.ChatResponse;
import com.aia.llm.ToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class AgentLoop {

    private static final int DEFAULT_MAX_ITERATIONS = 10;

    private final ChatModel model;
    private final ToolRegistry tools;
    private final String systemPrompt;
    private final int maxIterations;

    public AgentLoop(ChatModel model, ToolRegistry tools, String systemPrompt) {
        this(model, tools, systemPrompt, DEFAULT_MAX_ITERATIONS);
    }

    public AgentLoop(ChatModel model, ToolRegistry tools, String systemPrompt, int maxIterations) {
        this.model = model;
        this.tools = tools;
        this.systemPrompt = systemPrompt;
        this.maxIterations = maxIterations;
    }

    public String run(String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        messages.add(ChatMessage.user(userMessage));

        for (int i = 0; i < maxIterations; i++) {
            ChatRequest request = new ChatRequest(List.copyOf(messages), tools.toToolDefinitions(),
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

    private ToolResult executeTool(ToolCall call) {
        Optional<Tool> tool = tools.get(call.name());
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
