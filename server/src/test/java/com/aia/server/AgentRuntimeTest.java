package com.aia.server;

import com.aia.core.AgentConfig;
import com.aia.core.ConfigRepository;
import com.aia.core.DefaultToolResolver;
import com.aia.core.InMemoryConfigRepository;
import com.aia.core.SkillConfig;
import com.aia.core.Tool;
import com.aia.core.ToolInvocation;
import com.aia.core.ToolRegistry;
import com.aia.core.ToolResolver;
import com.aia.core.ToolResult;
import com.aia.llm.ChatMessage;
import com.aia.llm.ChatModel;
import com.aia.llm.ChatModelCapability;
import com.aia.llm.ChatRequest;
import com.aia.llm.ChatResponse;
import com.aia.llm.ToolDefinition;
import com.aia.llm.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeTest {

    static class FakeChatModel implements ChatModel {
        @Override
        public ChatResponse chat(ChatRequest request) {
            return new ChatResponse(ChatMessage.assistant("answer"), "stop", new Usage(1, 1));
        }

        @Override
        public Set<ChatModelCapability> capabilities() {
            return Set.of(ChatModelCapability.TOOL_CALLING);
        }
    }

    static class FakeTool implements Tool {
        private final String name;

        FakeTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "d";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of();
        }

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            return ToolResult.ok("ok");
        }
    }

    private AgentRuntime runtime(List<String> skillToolNames, ToolRegistry tools) {
        ConfigRepository repo = new InMemoryConfigRepository();
        repo.saveAgent(new AgentConfig("a1", "sys", "model-x", List.of(), List.of("s1"), null, null));
        repo.saveSkill(new SkillConfig("s1", "d", "i", skillToolNames));
        return new AgentRuntime(repo, tools, name -> new FakeChatModel());
    }

    @Test
    void chatReturnsAnswer() {
        ToolRegistry tools = new ToolRegistry();
        tools.register(new FakeTool("t1"));
        assertEquals("answer", runtime(List.of("t1"), tools).chat("a1", "hi"));
    }

    @Test
    void chatUnknownAgentThrows() {
        assertThrows(IllegalArgumentException.class, () -> runtime(List.of(), new ToolRegistry()).chat("nope", "hi"));
    }

    @Test
    void skillToolIgnoredWhenNotRegistered() {
        assertEquals("answer", runtime(List.of("missing"), new ToolRegistry()).chat("a1", "hi"));
    }

    static class CapturingChatModel implements ChatModel {
        ChatRequest lastRequest;

        @Override
        public ChatResponse chat(ChatRequest request) {
            lastRequest = request;
            return new ChatResponse(ChatMessage.assistant("answer"), "stop", new Usage(1, 1));
        }

        @Override
        public Set<ChatModelCapability> capabilities() {
            return Set.of(ChatModelCapability.TOOL_CALLING);
        }

        List<String> visibleTools() {
            return lastRequest.tools().stream().map(ToolDefinition::name).toList();
        }
    }

    private CapturingChatModel withAgent(List<String> toolNames, ToolRegistry tools) {
        return withAgent(toolNames, List.of(), tools, new DefaultToolResolver());
    }

    private CapturingChatModel withAgent(List<String> toolNames, List<String> skillToolNames, ToolRegistry tools,
                                         ToolResolver resolver) {
        ConfigRepository repo = new InMemoryConfigRepository();
        repo.saveAgent(new AgentConfig("a1", "sys", "model-x", toolNames, List.of("s1"), null, null));
        repo.saveSkill(new SkillConfig("s1", "d", "i", skillToolNames));
        CapturingChatModel model = new CapturingChatModel();
        new AgentRuntime(repo, tools, name -> model, resolver).chat("a1", "hi");
        return model;
    }

    private ToolRegistry twoTools() {
        ToolRegistry tools = new ToolRegistry();
        tools.register(new FakeTool("order"));
        tools.register(new FakeTool("logistics"));
        return tools;
    }

    @Test
    void nullToolNamesExposesAllRegisteredTools() {
        assertEquals(List.of("order", "logistics"), withAgent(null, twoTools()).visibleTools());
    }

    @Test
    void starToolNamesExposesAllRegisteredTools() {
        assertEquals(List.of("order", "logistics"), withAgent(List.of("*"), twoTools()).visibleTools());
    }

    @Test
    void emptyToolNamesExposesNoTools() {
        assertTrue(withAgent(List.of(), twoTools()).visibleTools().isEmpty());
    }

    @Test
    void whitelistLimitsExposedTools() {
        assertEquals(List.of("order"), withAgent(List.of("order"), twoTools()).visibleTools());
    }

    @Test
    void skillToolsStillExposedWhenGlobalScopeIsEmpty() {
        assertEquals(List.of("order"), withAgent(List.of(), List.of("order"), twoTools(), new DefaultToolResolver())
                .visibleTools());
    }

    @Test
    void customResolverOverridesDefaultPolicy() {
        ToolResolver denyAll = (agent, skills, registry) -> List.of();

        assertTrue(withAgent(List.of("*"), List.of(), twoTools(), denyAll).visibleTools().isEmpty());
    }
}
