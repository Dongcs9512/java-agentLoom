package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.core.AgentConfig;
import io.github.dongcs9512.agentloom.core.InMemoryConfigRepository;
import io.github.dongcs9512.agentloom.core.ToolRegistry;
import io.github.dongcs9512.agentloom.core.session.InMemorySessionStore;
import io.github.dongcs9512.agentloom.core.session.Session;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AgentRuntimeHumanHandoffTest {

    private AgentRuntime runtime(InMemorySessionStore store) {
        InMemoryConfigRepository repo = new InMemoryConfigRepository();
        repo.saveAgent(new AgentConfig("a1", "sys", "m", List.of("*"), List.of(), null, null));
        return new AgentRuntime(repo, new ToolRegistry(), name -> {
            throw new AssertionError("已转人工时不应该调用模型");
        }, new io.github.dongcs9512.agentloom.core.DefaultToolResolver(),
                new SessionSupport(store, null, null, null));
    }

    @Test
    void humanHandledSessionOnlyRecordsMessage() {
        InMemorySessionStore store = new InMemorySessionStore();
        Session session = new Session("s1", "a1");
        session.setOperator("human:张三");
        store.save(session);

        ChatReply reply = runtime(store).chat("a1", "s1", "我已经转人工了吗");

        assertEquals("waiting_human", reply.status());
        assertNull(reply.answer());
        assertEquals(1, store.load("s1").orElseThrow().messages().size(), "用户消息要入库等坐席处理");
    }

    @Test
    void aiHandlesNormallyWhenOperatorIsAi() {
        InMemorySessionStore store = new InMemorySessionStore();
        InMemoryConfigRepository repo = new InMemoryConfigRepository();
        repo.saveAgent(new AgentConfig("a1", "sys", "m", List.of("*"), List.of(), null, null));
        AgentRuntime runtime = new AgentRuntime(repo, new ToolRegistry(), name -> new io.github.dongcs9512.agentloom.llm.ChatModel() {
            @Override
            public io.github.dongcs9512.agentloom.llm.ChatResponse chat(io.github.dongcs9512.agentloom.llm.ChatRequest request) {
                return new io.github.dongcs9512.agentloom.llm.ChatResponse(
                        io.github.dongcs9512.agentloom.llm.ChatMessage.assistant("AI 回答"), "stop",
                        new io.github.dongcs9512.agentloom.llm.Usage(1, 1));
            }

            @Override
            public java.util.Set<io.github.dongcs9512.agentloom.llm.ChatModelCapability> capabilities() {
                return java.util.Set.of(io.github.dongcs9512.agentloom.llm.ChatModelCapability.TOOL_CALLING);
            }
        }, new io.github.dongcs9512.agentloom.core.DefaultToolResolver(),
                new SessionSupport(store, null, null, null));

        Session session = new Session("s1", "a1");
        session.setOperator("ai:a1");
        store.save(session);

        ChatReply reply = runtime.chat("a1", "s1", "你好");

        assertEquals("ok", reply.status());
        assertEquals("AI 回答", reply.answer());
    }
}
