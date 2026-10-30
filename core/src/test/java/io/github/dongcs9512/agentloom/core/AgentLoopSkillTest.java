package io.github.dongcs9512.agentloom.core;

import io.github.dongcs9512.agentloom.llm.ChatMessage;
import io.github.dongcs9512.agentloom.llm.ChatModel;
import io.github.dongcs9512.agentloom.llm.ChatModelCapability;
import io.github.dongcs9512.agentloom.llm.ChatRequest;
import io.github.dongcs9512.agentloom.llm.ChatResponse;
import io.github.dongcs9512.agentloom.llm.ToolCall;
import io.github.dongcs9512.agentloom.llm.Usage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopSkillTest {

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
        int invokeCount = 0;

        FakeTool(String name, ToolResult result) {
            this.name = name;
            this.result = result;
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
            invokeCount++;
            return result;
        }
    }

    private static Skill skill(String name, Tool... tools) {
        return new Skill() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "技能描述-" + name;
            }

            @Override
            public String instructions() {
                return "技能指令-" + name;
            }

            @Override
            public List<Tool> tools() {
                return List.of(tools);
            }

            @Override
            public Map<String, Object> resources() {
                return Map.of();
            }
        };
    }

    @Test
    void skillToolIsInvoked() {
        FakeTool doubleTool = new FakeTool("double", ToolResult.ok("10"));
        Skill s = skill("math", doubleTool);

        FakeChatModel model = new FakeChatModel(List.of(
                new ChatResponse(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("c1", "double", Map.of("number", 5)))),
                        "tool_calls", new Usage(1, 1)),
                new ChatResponse(ChatMessage.assistant("结果是 10"), "stop", new Usage(1, 1))));

        String answer = new AgentLoop(model, new ToolRegistry(), List.of(s), "sys").run("x");

        assertEquals("结果是 10", answer);
        assertEquals(1, doubleTool.invokeCount);
    }

    @Test
    void systemPromptIncludesSkillInstructions() {
        Skill s = skill("logistics");
        FakeChatModel model = new FakeChatModel(List.of(
                new ChatResponse(ChatMessage.assistant("ok"), "stop", new Usage(1, 1))));

        new AgentLoop(model, new ToolRegistry(), List.of(s), "sys").run("x");

        String system = model.requests.get(0).messages().get(0).content();
        assertTrue(system.contains("logistics"));
        assertTrue(system.contains("技能指令-logistics"));
    }
}
