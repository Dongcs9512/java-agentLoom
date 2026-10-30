package io.github.dongcs9512.agentloom.core.workflow;

import io.github.dongcs9512.agentloom.core.session.Session;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowEngineTest {

    /** 假宿主：记录调用，并在 chat 时按需往会话里写槽位，模拟模型收集信息。 */
    static class FakeHost implements WorkflowHost {
        final List<String> calls = new ArrayList<>();
        BiConsumer<Session, String> onChat = (session, message) -> {
        };
        Optional<String> routed = Optional.empty();

        @Override
        public String chat(String agentId, Session session, String userMessage) {
            calls.add(agentId + ":" + userMessage);
            onChat.accept(session, userMessage);
            return "answer-from-" + agentId;
        }

        @Override
        public Optional<String> route(Session session) {
            calls.add("route");
            return routed;
        }
    }

    private static WorkflowStep collect(String id, List<String> slots, int maxTurns) {
        return new WorkflowStep(id, StepType.COLLECT, slots, maxTurns, null, null, null, null, null);
    }

    private static WorkflowStep dispatch(String id) {
        return new WorkflowStep(id, StepType.DISPATCH, List.of(), null, "cascade", null, null, null, null);
    }

    private static WorkflowStep call(String id, String agent) {
        return new WorkflowStep(id, StepType.CALL, List.of(), null, null, agent, null, null, null);
    }

    private static WorkflowStep condition(String id, String when, String then, String otherwise) {
        return new WorkflowStep(id, StepType.CONDITION, List.of(), null, null, null, when, then, otherwise);
    }

    private static WorkflowDefinition legalFlow() {
        return new WorkflowDefinition("legal", "intake", List.of(
                collect("collect", List.of("incident"), 3),
                dispatch("dispatch"),
                call("answer", "@routed")));
    }

    private final WorkflowEngine engine = new WorkflowEngine();

    @Test
    void collectAsksQuestionAndWaitsForUser() {
        FakeHost host = new FakeHost();

        WorkflowResult result = engine.step(legalFlow(), new Session("s1", "intake"), "我被人辞退了", host);

        assertFalse(result.finished());
        assertEquals("collect", result.stepId());
        assertEquals("answer-from-intake", result.answer());
        assertEquals(1, host.calls.size(), "还没收集齐，不应该分派或调用专业 agent");
    }

    @Test
    void collectCompletesThenDispatchAndCallInSameTurn() {
        FakeHost host = new FakeHost();
        host.onChat = (session, message) -> session.putSlot("incident", "被辞退");
        host.routed = Optional.of("specialist");

        WorkflowResult result = engine.step(legalFlow(), new Session("s1", "intake"), "我被人辞退了", host);

        assertTrue(result.finished());
        assertEquals("answer-from-specialist", result.answer());
        assertTrue(result.trace().contains("collect:collect"));
        assertTrue(result.trace().contains("dispatch:specialist"));
        assertTrue(result.trace().contains("call:specialist"));
    }

    @Test
    void collectStaysUntilMaxTurnsThenAdvances() {
        FakeHost host = new FakeHost();   // 永远不填槽位
        host.routed = Optional.of("specialist");
        WorkflowDefinition flow = new WorkflowDefinition("legal", "intake", List.of(
                collect("collect", List.of("incident"), 2),
                dispatch("dispatch"),
                call("answer", "@routed")));
        Session session = new Session("s1", "intake");

        WorkflowResult first = engine.step(flow, session, "第一句", host);
        assertFalse(first.finished(), "第一轮还没到上限，继续追问");

        WorkflowResult second = engine.step(flow, session, "第二句", host);
        assertTrue(second.finished(), "到达轮次上限后应继续往下走");
        assertTrue(second.trace().contains("collect:max-turns"));
    }

    @Test
    void dispatchWithoutRouteAsksForMore() {
        FakeHost host = new FakeHost();
        host.onChat = (session, message) -> session.putSlot("incident", "被辞退");
        host.routed = Optional.empty();

        WorkflowResult result = engine.step(legalFlow(), new Session("s1", "intake"), "我被人辞退了", host);

        assertFalse(result.finished());
        assertEquals("dispatch", result.stepId());
        assertTrue(result.answer().contains("哪类问题"));
    }

    @Test
    void conditionBranchesToDifferentSteps() {
        FakeHost host = new FakeHost();
        host.onChat = (session, message) -> session.putSlot("amount", "20000");
        WorkflowDefinition flow = new WorkflowDefinition("w", "intake", List.of(
                collect("collect", List.of("amount"), 3),
                condition("check", "slots.amount > 10000", "handoff", "auto"),
                call("handoff", "human"),
                call("auto", "bot")));

        WorkflowResult result = engine.step(flow, new Session("s1", "intake"), "欠我两万", host);

        assertEquals("answer-from-human", result.answer());
        assertTrue(result.trace().contains("condition:then->handoff"));
    }

    @Test
    void conditionFalseBranch() {
        FakeHost host = new FakeHost();
        host.onChat = (session, message) -> session.putSlot("amount", "500");
        WorkflowDefinition flow = new WorkflowDefinition("w", "intake", List.of(
                collect("collect", List.of("amount"), 3),
                condition("check", "slots.amount > 10000", "handoff", "auto"),
                call("handoff", "human"),
                call("auto", "bot")));

        WorkflowResult result = engine.step(flow, new Session("s1", "intake"), "欠我五百", host);

        assertEquals("answer-from-bot", result.answer());
        assertTrue(result.trace().contains("condition:otherwise->auto"));
    }

    @Test
    void conditionWithoutTargetEndsFlow() {
        FakeHost host = new FakeHost();
        host.onChat = (session, message) -> session.putSlot("amount", "20000");
        WorkflowDefinition flow = new WorkflowDefinition("w", "intake", List.of(
                collect("collect", List.of("amount"), 3),
                condition("check", "slots.amount > 10000", "", "auto"),
                call("auto", "bot")));

        WorkflowResult result = engine.step(flow, new Session("s1", "intake"), "欠我两万", host);

        assertTrue(result.finished());
        assertEquals("", result.answer());
    }

    @Test
    void stateIsClearedAfterFinishSoNextMessageRestarts() {
        FakeHost host = new FakeHost();
        host.onChat = (session, message) -> session.putSlot("incident", "被辞退");
        host.routed = Optional.of("specialist");
        Session session = new Session("s1", "intake");

        engine.step(legalFlow(), session, "第一轮", host);
        assertEquals("", session.slot(WorkflowEngine.SLOT_STEP));

        WorkflowResult again = engine.step(legalFlow(), session, "新问题", host);
        assertTrue(again.trace().contains("collect:collect"), "应该从第一步重新开始");
    }

    @Test
    void callingStepUsesRoutedAgent() {
        FakeHost host = new FakeHost();
        host.onChat = (session, message) -> session.putSlot("incident", "被辞退");
        host.routed = Optional.of("legal-labor");

        engine.step(legalFlow(), new Session("s1", "intake"), "被辞退", host);

        assertTrue(host.calls.contains("legal-labor:请根据以下情况给出分析：\n- incident：被辞退\n"),
                "CALL 步骤应把收集到的案情整理成任务交给分派结果: " + host.calls);
    }

    @Test
    void cyclicConditionIsStoppedByGuard() {
        FakeHost host = new FakeHost();
        WorkflowDefinition flow = new WorkflowDefinition("w", "intake", List.of(
                new WorkflowStep("loop", StepType.CONDITION, List.of(), null, null, null,
                        "slots.x == \"\"", "loop", "loop")));

        assertThrows(IllegalStateException.class,
                () -> engine.step(flow, new Session("s1", "intake"), "hi", host));
    }

    @Test
    void definitionValidationCatchesConfigErrors() {
        WorkflowDefinition badCondition = new WorkflowDefinition("w", "intake", List.of(
                new WorkflowStep("c", StepType.CONDITION, List.of(), null, null, null, "amount > 1", "", "")));
        assertThrows(IllegalArgumentException.class, badCondition::validate);

        WorkflowDefinition missingTarget = new WorkflowDefinition("w", "intake", List.of(
                new WorkflowStep("c", StepType.CONDITION, List.of(), null, null, null,
                        "slots.a == 1", "nope", "")));
        assertThrows(IllegalArgumentException.class, missingTarget::validate);

        WorkflowDefinition noAgent = new WorkflowDefinition("w", "intake", List.of(
                new WorkflowStep("c", StepType.CALL, List.of(), null, null, "", null, null, null)));
        assertThrows(IllegalArgumentException.class, noAgent::validate);

        WorkflowDefinition noSlots = new WorkflowDefinition("w", "intake", List.of(
                new WorkflowStep("c", StepType.COLLECT, List.of(), 3, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, noSlots::validate);

        legalFlow().validate();
    }
}
