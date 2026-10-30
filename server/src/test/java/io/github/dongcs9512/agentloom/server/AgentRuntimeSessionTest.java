package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.core.AgentConfig;
import io.github.dongcs9512.agentloom.core.InMemoryConfigRepository;
import io.github.dongcs9512.agentloom.core.ToolRegistry;
import io.github.dongcs9512.agentloom.core.session.InMemoryAnswerCache;
import io.github.dongcs9512.agentloom.core.session.InMemorySessionStore;
import io.github.dongcs9512.agentloom.core.session.RuleBasedRetrievalRouter;
import io.github.dongcs9512.agentloom.core.session.Session;
import io.github.dongcs9512.agentloom.core.session.SessionStore;
import io.github.dongcs9512.agentloom.core.session.Slot;
import io.github.dongcs9512.agentloom.llm.ChatMessage;
import io.github.dongcs9512.agentloom.llm.ChatModel;
import io.github.dongcs9512.agentloom.llm.ChatModelCapability;
import io.github.dongcs9512.agentloom.llm.ChatRequest;
import io.github.dongcs9512.agentloom.llm.ChatResponse;
import io.github.dongcs9512.agentloom.llm.Usage;
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
        return new AgentRuntime(repo, new ToolRegistry(), name -> model, new io.github.dongcs9512.agentloom.core.DefaultToolResolver(),
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

    @Test
    void requiredSlotsCompleteDispatchesToSpecialistDeterministically() {
        InMemorySessionStore store = new InMemorySessionStore();
        Session existing = new Session("s1", "intake");
        existing.putSlot("incident", "公司欠薪三个月");
        store.save(existing);

        ScriptedModel intakeModel = new ScriptedModel(List.of(answer("接待回答")));
        ScriptedModel specialistModel = new ScriptedModel(List.of(answer("专业律师分析")));

        InMemoryConfigRepository repo = new InMemoryConfigRepository();
        repo.saveAgent(new AgentConfig("intake", "接待", "intake-model", List.of("*"), List.of(), null, null,
                List.of(new Slot("incident", "什么事？", true))));
        repo.saveAgent(new AgentConfig("specialist", "律师", "specialist-model", List.of("*"), List.of(),
                null, null, List.of()));

        AgentRuntime runtime = new AgentRuntime(repo, new ToolRegistry(), name ->
                "specialist-model".equals(name) ? specialistModel : intakeModel,
                new io.github.dongcs9512.agentloom.core.DefaultToolResolver(),
                new SessionSupport(store, null, null, null, (text, session) -> java.util.Optional.of("specialist")));

        String result = runtime.chat("intake", "s1", "那我该怎么办").answer();

        assertEquals("专业律师分析", result);
        assertTrue(intakeModel.requests.isEmpty(), "必填齐了应直接交给专业 agent，不再走接待流程");
        assertEquals("specialist", store.load("s1").orElseThrow().slot("__scenario"));
    }

    @Test
    void dispatchHappensOnlyOncePerSession() {
        InMemorySessionStore store = new InMemorySessionStore();
        Session existing = new Session("s1", "intake");
        existing.putSlot("incident", "公司欠薪");
        store.save(existing);

        ScriptedModel intakeModel = new ScriptedModel(List.of(answer("接待回答")));
        ScriptedModel specialistModel = new ScriptedModel(List.of(answer("专业律师分析")));

        InMemoryConfigRepository repo = new InMemoryConfigRepository();
        repo.saveAgent(new AgentConfig("intake", "接待", "intake-model", List.of("*"), List.of(), null, null,
                List.of(new Slot("incident", "什么事？", true))));
        repo.saveAgent(new AgentConfig("specialist", "律师", "specialist-model", List.of("*"), List.of(),
                null, null, List.of()));

        AgentRuntime runtime = new AgentRuntime(repo, new ToolRegistry(), name ->
                "specialist-model".equals(name) ? specialistModel : intakeModel,
                new io.github.dongcs9512.agentloom.core.DefaultToolResolver(),
                new SessionSupport(store, null, null, null, (text, session) -> java.util.Optional.of("specialist")));

        runtime.chat("intake", "s1", "那我该怎么办");
        runtime.chat("intake", "s1", "还有别的办法吗");

        assertEquals(1, specialistModel.requests.size(), "只在首次分派时调用专业 agent");
        assertEquals(1, intakeModel.requests.size(), "分派之后应回到接待 agent 处理后续问题");
    }
}
