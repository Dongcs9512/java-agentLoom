package io.github.dongcs9512.agentloom.core;

import io.github.dongcs9512.agentloom.llm.ChatMessage;
import io.github.dongcs9512.agentloom.llm.ChatMessageRole;
import io.github.dongcs9512.agentloom.llm.ChatModel;
import io.github.dongcs9512.agentloom.llm.ChatModelCapability;
import io.github.dongcs9512.agentloom.llm.ChatRequest;
import io.github.dongcs9512.agentloom.llm.ChatResponse;
import io.github.dongcs9512.agentloom.llm.ToolCall;
import io.github.dongcs9512.agentloom.llm.ToolDefinition;
import io.github.dongcs9512.agentloom.llm.Usage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopTest {

    static class FakeChatModel implements ChatModel {
        private final List<ChatResponse> responses;
        private final List<ChatRequest> requests = new ArrayList<>();
        private int index = 0;

        FakeChatModel(List<ChatResponse> responses) {
            this.responses = responses;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            requests.add(request);
            ChatResponse r = responses.get(Math.min(index, responses.size() - 1));
            index++;
            return r;
        }

        @Override
        public Set<ChatModelCapability> capabilities() {
            return Set.of(ChatModelCapability.TOOL_CALLING);
        }
    }

    static class FakeTool implements Tool {
        private final String name;
        private final ToolResult result;
        private final RuntimeException error;
        int invokeCount = 0;
        ToolInvocation lastInvocation;

        FakeTool(String name, ToolResult result, RuntimeException error) {
            this.name = name;
            this.result = result;
            this.error = error;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "desc";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of();
        }

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            invokeCount++;
            lastInvocation = invocation;
            if (error != null) {
                throw error;
            }
            return result;
        }
    }

    private static ChatResponse toolCallResponse(String name, Map<String, Object> args) {
        return new ChatResponse(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("c1", name, args))),
                "tool_calls", new Usage(1, 1));
    }

    @Test
    void runsToolThenReturnsAnswer() {
        ToolRegistry registry = new ToolRegistry();
        FakeTool doubleTool = new FakeTool("double", ToolResult.ok("10"), null);
        registry.register(doubleTool);

        FakeChatModel model = new FakeChatModel(List.of(
                toolCallResponse("double", Map.of("number", 5)),
                new ChatResponse(ChatMessage.assistant("结果是 10"), "stop", new Usage(1, 1))));

        String answer = new AgentLoop(model, registry, "sys").run("把5翻倍");

        assertEquals("结果是 10", answer);
        assertEquals(1, doubleTool.invokeCount);
        assertEquals(5, doubleTool.lastInvocation.arguments().get("number"));
        ChatRequest second = model.requests.get(1);
        assertTrue(second.messages().stream().anyMatch(m ->
                m.role() == ChatMessageRole.TOOL && "c1".equals(m.toolCallId()) && "10".equals(m.content())));
    }

    @Test
    void missingToolFeedsError() {
        FakeChatModel model = new FakeChatModel(List.of(
                toolCallResponse("missing", Map.of()),
                new ChatResponse(ChatMessage.assistant("done"), "stop", new Usage(1, 1))));

        new AgentLoop(model, new ToolRegistry(), "sys").run("x");

        ChatRequest second = model.requests.get(1);
        assertTrue(second.messages().stream().anyMatch(m ->
                m.role() == ChatMessageRole.TOOL && "未找到工具: missing".equals(m.content())));
    }

    @Test
    void toolExceptionFeedsError() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FakeTool("boom", null, new RuntimeException("boom")));

        FakeChatModel model = new FakeChatModel(List.of(
                toolCallResponse("boom", Map.of()),
                new ChatResponse(ChatMessage.assistant("done"), "stop", new Usage(1, 1))));

        new AgentLoop(model, registry, "sys").run("x");

        ChatRequest second = model.requests.get(1);
        assertTrue(second.messages().stream().anyMatch(m ->
                m.role() == ChatMessageRole.TOOL && "boom".equals(m.content())));
    }

    @Test
    void nullContentNoToolCallsReturnsEmpty() {
        FakeChatModel model = new FakeChatModel(List.of(
                new ChatResponse(ChatMessage.assistant(null), "stop", new Usage(1, 1))));

        String answer = new AgentLoop(model, new ToolRegistry(), "sys").run("x");

        assertEquals("", answer);
    }

    @Test
    void maxIterationsThrows() {
        FakeChatModel model = new FakeChatModel(List.of(toolCallResponse("t", Map.of())));

        AgentLoop loop = new AgentLoop(model, new ToolRegistry(), "sys", 2);

        assertThrows(IllegalStateException.class, () -> loop.run("x"));
    }

    @Test
    void explicitToolListIsExposedVerbatim() {
        FakeTool a = new FakeTool("a", ToolResult.ok("x"), null);
        FakeTool b = new FakeTool("b", ToolResult.ok("y"), null);
        FakeChatModel model = new FakeChatModel(List.of(
                new ChatResponse(ChatMessage.assistant("ok"), "stop", new Usage(1, 1))));

        new AgentLoop(model, List.of(a, b), "sys").run("hi");

        assertEquals(List.of("a", "b"),
                model.requests.get(0).tools().stream().map(ToolDefinition::name).toList());
    }

    @Test
    void emptyToolListExposesNoTools() {
        FakeChatModel model = new FakeChatModel(List.of(
                new ChatResponse(ChatMessage.assistant("ok"), "stop", new Usage(1, 1))));

        new AgentLoop(model, List.of(), "sys").run("hi");

        assertTrue(model.requests.get(0).tools().isEmpty());
    }

    @Test
    void explicitToolListExecutesWithoutRegistry() {
        FakeTool a = new FakeTool("a", ToolResult.ok("done-a"), null);
        FakeChatModel model = new FakeChatModel(List.of(
                toolCallResponse("a", Map.of()),
                new ChatResponse(ChatMessage.assistant("ok"), "stop", new Usage(1, 1))));

        new AgentLoop(model, List.of(a), "sys").run("hi");

        assertEquals(1, a.invokeCount);
    }

    @Test
    void runWithExplicitConversationUsesItVerbatim() {
        FakeChatModel model = new FakeChatModel(List.of(
                new ChatResponse(ChatMessage.assistant("ok"), "stop", new Usage(1, 1))));
        List<ChatMessage> conversation = List.of(
                ChatMessage.system("历史 system"),
                ChatMessage.user("第一句"),
                ChatMessage.assistant("第一答"),
                ChatMessage.user("第二句"));

        new AgentLoop(model, List.of(), "sys").run(conversation);

        ChatRequest first = model.requests.get(0);
        assertEquals(4, first.messages().size());
        assertEquals("历史 system", first.messages().get(0).content());
        assertEquals("第二句", first.messages().get(3).content());
    }
}
