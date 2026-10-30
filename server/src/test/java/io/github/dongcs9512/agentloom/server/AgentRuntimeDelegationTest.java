package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.core.AgentConfig;
import io.github.dongcs9512.agentloom.core.InMemoryConfigRepository;
import io.github.dongcs9512.agentloom.core.ToolRegistry;
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

class AgentRuntimeDelegationTest {

    static class ScriptedModel implements ChatModel {
        private final List<ChatResponse> responses;
        final List<ChatRequest> requests = new ArrayList<>();
        private int index = 0;

        ScriptedModel(List<ChatResponse> responses) {
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

    @Test
    void parentAgentDelegatesToChildAgent() {
        InMemoryConfigRepository repo = new InMemoryConfigRepository();
        repo.saveAgent(new AgentConfig("parent", "你是总客服", "parent-model", List.of("*"), List.of(), null, null));
        repo.saveAgent(new AgentConfig("billing", "你只处理账单", "child-model", List.of(), List.of(), null, null));

        ScriptedModel parentModel = new ScriptedModel(List.of(
                callTool("delegate_to_billing", Map.of("task", "用户问退款进度")),
                answer("账单同事说：3 天内到账")));
        ScriptedModel childModel = new ScriptedModel(List.of(answer("退款 3 天内到账")));
        ToolRegistry registry = new ToolRegistry();

        AgentRuntime runtime = new AgentRuntime(repo, registry, name ->
                "parent-model".equals(name) ? parentModel : childModel);
        registry.register(runtime.subAgentTool("billing", "处理账单与退款问题"));

        String answer = runtime.chat("parent", "我的退款呢");

        assertEquals("账单同事说：3 天内到账", answer);
        assertTrue(childModel.requests.get(0).messages().stream()
                        .anyMatch(m -> "你是总客服".equals(m.content()) == false
                                && "你只处理账单".equals(m.content())),
                "子 agent 应使用自己的 system prompt");
        assertTrue(parentModel.requests.get(1).messages().stream()
                        .anyMatch(m -> "退款 3 天内到账".equals(m.content())),
                "子 agent 的回答应作为工具结果回给主 agent");
    }
}
