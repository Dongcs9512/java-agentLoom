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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubAgentToolTest {

    static class FakeChatModel implements ChatModel {
        private final List<ChatResponse> responses;
        final List<ChatRequest> requests = new ArrayList<>();
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

    private static ChatResponse answer(String content) {
        return new ChatResponse(ChatMessage.assistant(content), "stop", new Usage(1, 1));
    }

    private static ChatResponse callTool(String name, Map<String, Object> args) {
        return new ChatResponse(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("c1", name, args))),
                "tool_calls", new Usage(1, 1));
    }

    private static ToolInvocation invoke(String task) {
        return new ToolInvocation("c1", "delegate_to_billing", Map.of("task", task));
    }

    @Test
    void exposesNameAndSchema() {
        SubAgentTool tool = SubAgentTool.of("billing", "处理账单问题", () -> null);

        assertEquals("delegate_to_billing", tool.name());
        assertEquals("处理账单问题", tool.description());
        assertEquals("object", tool.parameters().get("type"));
        assertEquals(List.of("task"), tool.parameters().get("required"));
    }

    @Test
    void runsSubAgentAndReturnsItsAnswer() {
        AgentLoop child = new AgentLoop(new FakeChatModel(List.of(answer("已为你退款"))), List.of(), "子 agent");
        SubAgentTool tool = SubAgentTool.of("billing", "处理账单问题", () -> child);

        ToolResult result = tool.execute(invoke("我要退款"));

        assertFalse(result.isError());
        assertEquals("已为你退款", result.content());
    }

    @Test
    void passesTheTaskToTheSubAgentAsUserMessage() {
        FakeChatModel childModel = new FakeChatModel(List.of(answer("ok")));
        AgentLoop child = new AgentLoop(childModel, List.of(), "子 agent");
        SubAgentTool tool = SubAgentTool.of("billing", "d", () -> child);

        tool.execute(invoke("查一下订单 A123"));

        assertTrue(childModel.requests.get(0).messages().stream()
                .anyMatch(m -> "查一下订单 A123".equals(m.content())));
    }

    @Test
    void missingTaskReturnsError() {
        SubAgentTool tool = SubAgentTool.of("billing", "d", () -> null);

        ToolResult result = tool.execute(new ToolInvocation("c1", "delegate_to_billing", Map.of()));

        assertTrue(result.isError());
        assertTrue(result.content().contains("task"), result.content());
    }

    @Test
    void childFailureBecomesErrorResult() {
        SubAgentTool tool = SubAgentTool.of("billing", "d", () -> {
            throw new IllegalStateException("子 agent 不可用");
        });

        ToolResult result = tool.execute(invoke("x"));

        assertTrue(result.isError());
        assertTrue(result.content().contains("子 agent 不可用"), result.content());
    }

    @Test
    void delegationDepthIsLimited() {
        FakeChatModel model = new FakeChatModel(List.of(callTool("delegate_to_billing", Map.of("task", "再来一次")),
                answer("退下了")));
        AgentLoop[] loopRef = new AgentLoop[1];
        SubAgentTool tool = new SubAgentTool("delegate_to_billing", "d", () -> loopRef[0], 1);
        loopRef[0] = new AgentLoop(model, List.of(tool), "子 agent");

        ToolResult result = tool.execute(invoke("开始"));

        assertFalse(result.isError());
        assertTrue(model.requests.get(1).messages().stream()
                        .anyMatch(m -> m.content() != null && m.content().contains("委派层级")),
                "超过深度上限时应把错误结果喂回模型");
    }
}
