package com.aia.server;

import com.aia.core.AgentConfig;
import com.aia.core.InMemoryConfigRepository;
import com.aia.core.ToolRegistry;
import com.aia.core.session.InMemoryAnswerCache;
import com.aia.core.session.InMemorySessionStore;
import com.aia.core.session.RuleBasedRetrievalRouter;
import com.aia.core.session.Session;
import com.aia.core.session.SessionStore;
import com.aia.core.session.Slot;
import com.aia.llm.ChatMessage;
import com.aia.llm.ChatModel;
import com.aia.llm.ChatModelCapability;
import com.aia.llm.ChatRequest;
import com.aia.llm.ChatResponse;
import com.aia.llm.Usage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeSessionTest {

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

    private AgentRuntime runtime(AgentConfig config, ScriptedModel model, SessionSupport support) {
        InMemoryConfigRepository repo = new InMemoryConfigRepository();
        repo.saveAgent(config);
        return new AgentRuntime(repo, new ToolRegistry(), name -> model, new com.aia.core.DefaultToolResolver(),
                support);
    }

    private AgentConfig config(List<Slot> slots) {
        return new AgentConfig("a1", "你是法律咨询助手", "m", List.of(), List.of(), null, null, slots);
    }

    @Test
    void secondTurnCarriesHistoryFromSameSession() {
        ScriptedModel model = new ScriptedModel(List.of(answer("第一次回答"), answer("第二次回答")));
        AgentRuntime runtime = runtime(config(List.of()), model,
                new SessionSupport(new InMemorySessionStore(), null, null, null));

        runtime.chat("a1", "s1", "我被人辞退了");
        String second = runtime.chat("a1", "s1", "那能要赔偿吗").answer();

        assertEquals("第二次回答", second);
        List<ChatMessage> sent = model.requests.get(1).messages();
        assertTrue(sent.stream().anyMatch(m -> "我被人辞退了".equals(m.content())), "应带上第一轮用户消息");
        assertTrue(sent.stream().anyMatch(m -> "第一次回答".equals(m.content())), "应带上第一轮助手回答");
    }

    @Test
    void sessionIsPersistedAcrossRuntimeInstances() {
        SessionStore store = new InMemorySessionStore();
        ScriptedModel model = new ScriptedModel(List.of(answer("答一"), answer("答二")));
        AgentRuntime first = runtime(config(List.of()), model, new SessionSupport(store, null, null, null));
        first.chat("a1", "s1", "第一句");

        AgentRuntime second = runtime(config(List.of()), model, new SessionSupport(store, null, null, null));
        second.chat("a1", "s1", "第二句");

        Session saved = store.load("s1").orElseThrow();
        assertEquals(4, saved.messages().size(), "两轮问答共 4 条消息");
    }

    @Test
    void greetingRouteSkipsModelEntirely() {
        ScriptedModel model = new ScriptedModel(List.of(answer("不该被调用")));
        AgentRuntime runtime = runtime(config(List.of()), model, new SessionSupport(null, null,
                new RuleBasedRetrievalRouter("您好，请问有什么可以帮您？", List.of()), null));

        ChatReply reply = runtime.chat("a1", "s1", "你好");

        assertEquals("您好，请问有什么可以帮您？", reply.answer());
        assertTrue(model.requests.isEmpty(), "寒暄不应调用模型");
    }

    @Test
    void repeatedQuestionIsServedFromCache() {
        ScriptedModel model = new ScriptedModel(List.of(answer("签收后 7 天内可退")));
        AgentRuntime runtime = runtime(config(List.of()), model, new SessionSupport(null,
                new InMemoryAnswerCache(10), null, null));

        ChatReply first = runtime.chat("a1", "s1", "退货怎么退");
        ChatReply second = runtime.chat("a1", "s2", "退货怎么退？");

        assertEquals("签收后 7 天内可退", second.answer());
        assertFalse(first.fromCache());
        assertTrue(second.fromCache(), "同样的问题第二次应命中缓存");
        assertEquals(1, model.requests.size(), "缓存命中不应再调模型");
    }

    @Test
    void slotPromptIsInjectedIntoSystemMessage() {
        ScriptedModel model = new ScriptedModel(List.of(answer("请问具体是什么事？")));
        List<Slot> slots = List.of(new Slot("incident", "具体是什么事？", true));
        AgentRuntime runtime = runtime(config(slots), model, new SessionSupport(new InMemorySessionStore(), null,
                null, null));

        runtime.chat("a1", "s1", "我想咨询劳动纠纷");

        String system = model.requests.get(0).messages().get(0).content();
        assertTrue(system.contains("信息收集"), system);
        assertTrue(system.contains("具体是什么事？"), system);
    }

    @Test
    void withoutSessionSupportBehavesSingleTurn() {
        ScriptedModel model = new ScriptedModel(List.of(answer("答一"), answer("答二")));
        AgentRuntime runtime = runtime(config(List.of()), model, SessionSupport.disabled());

        runtime.chat("a1", "第一句");
        runtime.chat("a1", "第二句");

        assertEquals(2, model.requests.get(1).messages().size(), "单轮模式只带 system + 当前问题");
    }
}
