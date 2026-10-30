package io.github.dongcs9512.agentloom.core.session;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeywordScenarioRouterTest {

    private static final List<KeywordScenarioRouter.Rule> RULES = List.of(
            new KeywordScenarioRouter.Rule("legal-labor", List.of("辞退", "欠薪", "加班费", "赔偿金")),
            new KeywordScenarioRouter.Rule("legal-contract", List.of("竞业", "保密", "试用期", "违约金")),
            new KeywordScenarioRouter.Rule("legal-welfare", List.of("社保", "公积金", "工伤", "产假")));

    private KeywordScenarioRouter router() {
        return new KeywordScenarioRouter(RULES, null);
    }

    @Test
    void routesBySingleKeyword() {
        assertEquals("legal-labor", router().route("公司把我辞退了", null).orElseThrow());
        assertEquals("legal-contract", router().route("竞业限制协议有效吗", null).orElseThrow());
        assertEquals("legal-welfare", router().route("公司没给我交社保", null).orElseThrow());
    }

    @Test
    void mostMatchedRuleWins() {
        String text = "公司辞退我还不给加班费，另外竞业协议也没补偿";

        assertEquals("legal-labor", router().route(text, null).orElseThrow());
    }

    @Test
    void noMatchWithoutDefaultReturnsEmpty() {
        assertTrue(router().route("今天天气不错", null).isEmpty());
    }

    @Test
    void fallsBackToDefaultAgent() {
        KeywordScenarioRouter withDefault = new KeywordScenarioRouter(RULES, "legal-labor");

        assertEquals("legal-labor", withDefault.route("今天天气不错", null).orElseThrow());
        assertTrue(withDefault.route(null, null).isPresent());
    }

    @Test
    void collectedSlotsParticipateInRouting() {
        Session session = new Session("s1", "a1");
        session.putSlot("incident", "被公司欠薪三个月");

        assertEquals("legal-labor", router().route("那我可以怎么办", session).orElseThrow());
    }

    @Test
    void rulesKeepConfiguredOrderForTies() {
        List<KeywordScenarioRouter.Rule> tied = List.of(
                new KeywordScenarioRouter.Rule("first", List.of("辞退")),
                new KeywordScenarioRouter.Rule("second", List.of("辞退")));

        assertEquals("first", new KeywordScenarioRouter(tied, null).route("被辞退", null).orElseThrow());
    }

    @Test
    void weightedKeywordBeatsSeveralGenericOnes() {
        List<KeywordScenarioRouter.Rule> weighted = List.of(
                new KeywordScenarioRouter.Rule("legal-labor", List.of("辞退", "赔偿")),
                new KeywordScenarioRouter.Rule("legal-welfare", List.of("社保"), Map.of("社保", 3)));

        String text = "公司让我签放弃社保的承诺书，不签就要辞退我，也没有赔偿";

        assertEquals("legal-welfare", new KeywordScenarioRouter(weighted, null).route(text, null).orElseThrow());
    }
}
