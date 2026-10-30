package com.aia.rag.routing;

import com.aia.core.session.Session;
import com.aia.rag.EmbeddingModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingScenarioRouterTest {

    private static final EmbeddingModel FAKE = new EmbeddingModel() {
        @Override
        public float[] embed(String text) {
            if (text.contains("社保") || text.contains("工伤")) {
                return new float[]{0, 1, 0};
            }
            if (text.contains("竞业") || text.contains("合同")) {
                return new float[]{0, 0, 1};
            }
            if (text.contains("辞退") || text.contains("工资") || text.contains("薪水")) {
                return new float[]{1, 0, 0};
            }
            return new float[]{0.577f, 0.577f, 0.577f};
        }

        @Override
        public int dimension() {
            return 3;
        }
    };

    private static final List<EmbeddingScenarioRouter.Rule> RULES = List.of(
            new EmbeddingScenarioRouter.Rule("legal-welfare", List.of("公司没交社保", "工伤怎么报")),
            new EmbeddingScenarioRouter.Rule("legal-contract", List.of("竞业协议是否有效", "劳动合同条款")),
            new EmbeddingScenarioRouter.Rule("legal-labor", List.of("被辞退有赔偿吗", "拖欠工资")));

    private EmbeddingScenarioRouter router() {
        return new EmbeddingScenarioRouter(FAKE, RULES, 0.9, null);
    }

    @Test
    void routesBySemanticNeighbour() {
        assertEquals("legal-welfare", router().route("社保一直没给我缴", null).orElseThrow());
        assertEquals("legal-contract", router().route("竞业限制协议管用吗", null).orElseThrow());
        assertEquals("legal-labor", router().route("老板不发薪水", null).orElseThrow());
    }

    @Test
    void belowThresholdFallsBackToDefault() {
        assertTrue(router().route("今天天气不错", null).isEmpty());

        EmbeddingScenarioRouter withDefault = new EmbeddingScenarioRouter(FAKE, RULES, 0.9, "legal-labor");
        assertEquals("legal-labor", withDefault.route("今天天气不错", null).orElseThrow());
    }

    @Test
    void collectedSlotsAreUsedInRouting() {
        Session session = new Session("s1", "a1");
        session.putSlot("incident", "工伤后公司不处理");

        assertEquals("legal-welfare", router().route("我该怎么办", session).orElseThrow());
    }

    @Test
    void emptyTextGoesToDefault() {
        assertTrue(router().route("", null).isEmpty());
    }
}
