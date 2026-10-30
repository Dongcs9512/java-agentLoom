package io.github.dongcs9512.agentloom.core.session;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CascadingScenarioRouterTest {

    @Test
    void firstRouterWithAnswerWins() {
        ScenarioRouter first = (text, session) -> Optional.empty();
        ScenarioRouter second = (text, session) -> Optional.of("from-second");
        ScenarioRouter third = (text, session) -> Optional.of("from-third");

        assertEquals("from-second", new CascadingScenarioRouter(List.of(first, second, third))
                .route("x", null).orElseThrow());
    }

    @Test
    void returnsEmptyWhenNoRouterMatches() {
        ScenarioRouter none = (text, session) -> Optional.empty();

        assertTrue(new CascadingScenarioRouter(List.of(none, none)).route("x", null).isEmpty());
    }

    @Test
    void keywordRouterRespectsMinScoreSoCascadeCanFallThrough() {
        KeywordScenarioRouter strict = new KeywordScenarioRouter(
                List.of(new KeywordScenarioRouter.Rule("legal-labor", List.of("辞退", "欠薪"),
                        java.util.Map.of("辞退", 1, "欠薪", 1))),
                null, 2);
        ScenarioRouter fallback = (text, session) -> Optional.of("semantic");

        // 只命中一个未加权关键词，得分 1 < minScore 2 → 交给下一级
        assertEquals("semantic", new CascadingScenarioRouter(List.of(strict, fallback))
                .route("被辞退了怎么办", null).orElseThrow());
    }
}
