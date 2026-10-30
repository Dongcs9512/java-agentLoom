package com.aia.core.session;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmScenarioRouterTest {

    static class FakeModel implements ChatModel {
        private final String reply;
        final List<ChatRequest> requests = new ArrayList<>();

        FakeModel(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            requests.add(request);
            return new ChatResponse(ChatMessage.assistant(reply), "stop", new Usage(1, 1));
        }

        @Override
        public Set<ChatModelCapability> capabilities() {
            return Set.of(ChatModelCapability.TOOL_CALLING);
        }
    }

    private static final List<LlmScenarioRouter.Category> CATEGORIES = List.of(
            new LlmScenarioRouter.Category("legal-labor", "辞退、欠薪、加班费"),
            new LlmScenarioRouter.Category("legal-welfare", "社保、工伤、三期"),
            new LlmScenarioRouter.Category("legal-contract", "竞业、合同条款"));

    @Test
    void returnsMatchingCategory() {
        FakeModel model = new FakeModel("legal-welfare");

        assertEquals("legal-welfare",
                new LlmScenarioRouter(model, CATEGORIES, null).route("公司没给我交社保", null).orElseThrow());
    }

    @Test
    void toleratesDecorativeOutput() {
        FakeModel model = new FakeModel("类别：legal-contract\n理由：涉及竞业协议");

        assertEquals("legal-contract",
                new LlmScenarioRouter(model, CATEGORIES, null).route("竞业协议有效吗", null).orElseThrow());
    }

    @Test
    void unknownFallsBackToDefault() {
        FakeModel model = new FakeModel("UNKNOWN");

        assertTrue(new LlmScenarioRouter(model, CATEGORIES, null).route("今天天气不错", null).isEmpty());
        assertEquals("legal-labor",
                new LlmScenarioRouter(model, CATEGORIES, "legal-labor").route("今天天气不错", null).orElseThrow());
    }

    @Test
    void promptListsEveryCategory() {
        FakeModel model = new FakeModel("legal-labor");

        new LlmScenarioRouter(model, CATEGORIES, null).route("被辞退了", null);

        String prompt = model.requests.get(0).messages().stream()
                .map(ChatMessage::content).reduce("", (a, b) -> a + b);
        CATEGORIES.forEach(c -> assertTrue(prompt.contains(c.agentId()), prompt));
    }

    @Test
    void collectedSlotsParticipateInClassification() {
        FakeModel model = new FakeModel("legal-welfare");
        Session session = new Session("s1", "a1");
        session.putSlot("incident", "工伤后公司不管");

        new LlmScenarioRouter(model, CATEGORIES, null).route("那我该怎么办", session);

        String prompt = model.requests.get(0).messages().stream()
                .map(ChatMessage::content).reduce("", (a, b) -> a + b);
        assertTrue(prompt.contains("工伤后公司不管"), prompt);
    }
}
