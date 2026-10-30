package io.github.dongcs9512.agentloom.core;

import io.github.dongcs9512.agentloom.llm.ChatChunk;
import io.github.dongcs9512.agentloom.llm.ChatMessage;
import io.github.dongcs9512.agentloom.llm.ChatModel;
import io.github.dongcs9512.agentloom.llm.ChatModelCapability;
import io.github.dongcs9512.agentloom.llm.ChatRequest;
import io.github.dongcs9512.agentloom.llm.ChatResponse;
import io.github.dongcs9512.agentloom.llm.ToolCall;
import io.github.dongcs9512.agentloom.llm.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentLoopStreamTest {

    static class FakeStreamChatModel implements ChatModel {
        private final List<List<ChatChunk>> script;
        private int call = 0;

        FakeStreamChatModel(List<List<ChatChunk>> script) {
            this.script = script;
        }

        @Override
        public void stream(ChatRequest request, Consumer<ChatChunk> onChunk) {
            List<ChatChunk> chunks = script.get(Math.min(call, script.size() - 1));
            call++;
            chunks.forEach(onChunk);
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            return new ChatResponse(ChatMessage.assistant(""), "stop", new Usage(1, 1));
        }

        @Override
        public Set<ChatModelCapability> capabilities() {
            return Set.of(ChatModelCapability.TOOL_CALLING);
        }
    }

    static class FakeTool implements Tool {
        int invokeCount = 0;

        @Override
        public String name() {
            return "double";
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
            invokeCount++;
            return ToolResult.ok("10");
        }
    }

    @Test
    void streamEmitsTextDeltas() {
        FakeStreamChatModel model = new FakeStreamChatModel(List.of(
                List.of(new ChatChunk("你"), new ChatChunk("好"))));
        StringBuilder sb = new StringBuilder();
        new AgentLoop(model, new ToolRegistry(), "sys").stream("x", sb::append);
        assertEquals("你好", sb.toString());
    }

    @Test
    void streamHandlesToolThenText() {
        FakeStreamChatModel model = new FakeStreamChatModel(List.of(
                List.of(new ChatChunk(null, List.of(new ToolCall("c1", "double", Map.of("number", 5))), "tool_calls")),
                List.of(new ChatChunk("10"))));
        FakeTool doubleTool = new FakeTool();
        ToolRegistry registry = new ToolRegistry();
        registry.register(doubleTool);

        StringBuilder sb = new StringBuilder();
        new AgentLoop(model, registry, "sys").stream("x", sb::append);

        assertEquals("10", sb.toString());
        assertEquals(1, doubleTool.invokeCount);
    }
}
