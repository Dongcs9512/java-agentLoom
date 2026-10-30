package com.aia.core;

import com.aia.core.session.KeywordScenarioRouter;
import com.aia.core.session.Session;
import com.aia.llm.ChatMessage;
import com.aia.llm.ChatModel;
import com.aia.llm.ChatModelCapability;
import com.aia.llm.ChatRequest;
import com.aia.llm.ChatResponse;
import com.aia.llm.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioDispatchToolTest {

    private static final KeywordScenarioRouter ROUTER = new KeywordScenarioRouter(List.of(
            new KeywordScenarioRouter.Rule("legal-labor", List.of("辞退", "欠薪")),
            new KeywordScenarioRouter.Rule("legal-welfare", List.of("社保", "工伤"))), null);

    private static AgentLoop answer(String text) {
        return new AgentLoop(new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return new ChatResponse(ChatMessage.assistant(text), "stop", new Usage(1, 1));
            }

            @Override
            public Set<ChatModelCapability> capabilities() {
                return Set.of(ChatModelCapability.TOOL_CALLING);
            }
        }, List.of(), "专业律师");
    }

    private ScenarioDispatchTool tool(Session session, Map<String, AgentLoop> loops) {
        return new ScenarioDispatchTool(ROUTER, session, loops::get);
    }

    private ToolInvocation invoke(String task) {
        return new ToolInvocation("c1", ScenarioDispatchTool.NAME, Map.of("task", task));
    }

    @Test
    void routesLaborCaseToLaborAgent() {
        ScenarioDispatchTool tool = tool(new Session("s1", "a1"), Map.of(
                "legal-labor", answer("劳动法意见"),
                "legal-welfare", answer("社保意见")));

        ToolResult result = tool.execute(invoke("公司把我辞退了，不给赔偿"));

        assertFalse(result.isError());
        assertTrue(result.content().contains("劳动法意见"), result.content());
        assertTrue(result.content().contains("legal-labor"), result.content());
    }

    @Test
    void routesWelfareCaseToWelfareAgent() {
        ScenarioDispatchTool tool = tool(new Session("s1", "a1"), Map.of(
                "legal-labor", answer("劳动法意见"),
                "legal-welfare", answer("社保意见")));

        ToolResult result = tool.execute(invoke("上班受伤公司不给报工伤"));

        assertTrue(result.content().contains("社保意见"), result.content());
    }

    @Test
    void collectedSlotsHelpRoutingWhenTaskIsVague() {
        Session session = new Session("s1", "a1");
        session.putSlot("incident", "公司欠薪三个月");
        ScenarioDispatchTool tool = tool(session, Map.of("legal-labor", answer("劳动法意见")));

        ToolResult result = tool.execute(invoke("那我该怎么办"));

        assertFalse(result.isError());
        assertTrue(result.content().contains("劳动法意见"), result.content());
    }

    @Test
    void unmatchedScenarioReturnsError() {
        ScenarioDispatchTool tool = tool(new Session("s1", "a1"), Map.of("legal-labor", answer("x")));

        ToolResult result = tool.execute(invoke("今天天气不错"));

        assertTrue(result.isError());
        assertTrue(result.content().contains("哪类法律问题"), result.content());
    }

    @Test
    void missingAgentConfigurationIsReported() {
        ScenarioDispatchTool tool = tool(new Session("s1", "a1"), Map.of());

        ToolResult result = tool.execute(invoke("公司把我辞退了"));

        assertTrue(result.isError());
        assertTrue(result.content().contains("尚未配置"), result.content());
    }

    @Test
    void missingTaskIsRejected() {
        ToolResult result = tool(new Session("s1", "a1"), Map.of())
                .execute(new ToolInvocation("c1", ScenarioDispatchTool.NAME, Map.of()));

        assertTrue(result.isError());
        assertTrue(result.content().contains("task"), result.content());
    }

    @Test
    void exposesOpenAiSchema() {
        ScenarioDispatchTool tool = tool(new Session("s1", "a1"), Map.of());

        assertEquals("delegate_to_specialist", tool.name());
        assertEquals(List.of("task"), tool.parameters().get("required"));
    }
}
