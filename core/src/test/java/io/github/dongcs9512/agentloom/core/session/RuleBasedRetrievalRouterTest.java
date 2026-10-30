package io.github.dongcs9512.agentloom.core.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuleBasedRetrievalRouterTest {

    private RouteResult route(String message) {
        return new RuleBasedRetrievalRouter("您好，我是法律咨询助手，请问有什么可以帮您？", List.of("谢谢", "再见"))
                .route(message, new Session("s1", "a1"));
    }

    @Test
    void blankMessageUsesCannedReply() {
        assertEquals(RouteDecision.CANNED, route("").decision());
        assertEquals(RouteDecision.CANNED, route("   ").decision());
    }

    @Test
    void greetingUsesCannedReply() {
        assertEquals(RouteDecision.CANNED, route("你好").decision());
        assertEquals(RouteDecision.CANNED, route("在吗？").decision());
        assertEquals(RouteDecision.CANNED, route("hello").decision());
    }

    @Test
    void extraChitchatPhrasesUseCannedReply() {
        assertEquals(RouteDecision.CANNED, route("谢谢").decision());
        assertEquals(RouteDecision.CANNED, route("再见").decision());
    }

    @Test
    void substantiveQuestionGoesToRetrieval() {
        assertEquals(RouteDecision.RETRIEVE, route("我要咨询劳动纠纷，公司把我辞退了").decision());
        assertEquals(RouteDecision.RETRIEVE, route("欠薪多久可以仲裁").decision());
    }

    @Test
    void longMessageContainingGreetingStillGoesToRetrieval() {
        assertEquals(RouteDecision.RETRIEVE,
                route("你好，我想问一下公司拖欠工资三个月了，我应该怎么走劳动仲裁流程").decision());
    }

    @Test
    void greetingCanBeAnsweredByModelInstead() {
        RuleBasedRetrievalRouter router = new RuleBasedRetrievalRouter("话术", List.of(), ChitchatMode.MODEL);

        assertEquals(RouteDecision.DIRECT, router.route("你好", new Session("s1", "a1")).decision());
    }

    @Test
    void cannedReplyCarriesConfiguredText() {
        RouteResult result = route("你好");

        assertEquals("您好，我是法律咨询助手，请问有什么可以帮您？", result.reply());
    }
}
