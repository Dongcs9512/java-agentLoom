package io.github.dongcs9512.agentloom.core;

import io.github.dongcs9512.agentloom.llm.ChatMessage;
import io.github.dongcs9512.agentloom.llm.ChatModel;
import io.github.dongcs9512.agentloom.llm.ChatModelCapability;
import io.github.dongcs9512.agentloom.llm.ChatRequest;
import io.github.dongcs9512.agentloom.llm.ChatResponse;
import io.github.dongcs9512.agentloom.llm.Usage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelSubAgentToolTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    static ChatModel answering(String answer) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return new ChatResponse(ChatMessage.assistant(answer), "stop", new Usage(1, 1));
            }

            @Override
            public Set<ChatModelCapability> capabilities() {
                return Set.of(ChatModelCapability.TOOL_CALLING);
            }
        };
    }

    static ChatModel failing(String message) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new IllegalStateException(message);
            }

            @Override
            public Set<ChatModelCapability> capabilities() {
                return Set.of(ChatModelCapability.TOOL_CALLING);
            }
        };
    }

    private ParallelSubAgentTool tool(Map<String, java.util.function.Supplier<AgentLoop>> loops) {
        return new ParallelSubAgentTool(loops, executor, 10);
    }

    private ToolInvocation invoke(List<Map<String, Object>> tasks) {
        return new ToolInvocation("c1", "parallel_delegate", Map.of("tasks", tasks));
    }

    @Test
    void runsAllTasksAndAggregatesAnswers() {
        ParallelSubAgentTool tool = tool(Map.of(
                "labor", () -> new AgentLoop(answering("劳动法意见"), List.of(), "劳动"),
                "contract", () -> new AgentLoop(answering("合同法意见"), List.of(), "合同")));

        ToolResult result = tool.execute(invoke(List.of(
                Map.of("agent", "labor", "task", "分析是否属于违法解除"),
                Map.of("agent", "contract", "task", "分析竞业条款是否有效"))));

        assertFalse(result.isError());
        assertTrue(result.content().contains("劳动法意见"), result.content());
        assertTrue(result.content().contains("合同法意见"), result.content());
        assertTrue(result.content().indexOf("劳动法意见") < result.content().indexOf("合同法意见"),
                "结果应按请求顺序聚合");
    }

    @Test
    void oneFailingTaskDoesNotFailTheWholeCall() {
        ParallelSubAgentTool tool = tool(Map.of(
                "labor", () -> new AgentLoop(answering("劳动法意见"), List.of(), "劳动"),
                "broken", () -> new AgentLoop(failing("模型超时"), List.of(), "坏")));

        ToolResult result = tool.execute(invoke(List.of(
                Map.of("agent", "labor", "task", "a"),
                Map.of("agent", "broken", "task", "b"))));

        assertFalse(result.isError());
        assertTrue(result.content().contains("劳动法意见"), result.content());
        assertTrue(result.content().contains("模型超时"), result.content());
    }

    @Test
    void unknownAgentIsReportedPerTask() {
        ParallelSubAgentTool tool = tool(Map.of("labor", () -> new AgentLoop(answering("x"), List.of(), "劳动")));

        ToolResult result = tool.execute(invoke(List.of(Map.of("agent", "ghost", "task", "a"))));

        assertTrue(result.content().contains("ghost"), result.content());
        assertTrue(result.content().contains("未知"), result.content());
    }

    @Test
    void missingTasksIsRejected() {
        ToolResult result = tool(Map.of()).execute(new ToolInvocation("c1", "parallel_delegate", Map.of()));

        assertTrue(result.isError());
        assertTrue(result.content().contains("tasks"), result.content());
    }

    @Test
    void emptyTasksIsRejected() {
        ToolResult result = tool(Map.of()).execute(invoke(List.of()));

        assertTrue(result.isError());
    }

    @Test
    void exposesOpenAiSchema() {
        ParallelSubAgentTool tool = tool(Map.of());

        assertEquals("parallel_delegate", tool.name());
        assertEquals("object", tool.parameters().get("type"));
        assertEquals(List.of("tasks"), tool.parameters().get("required"));
    }
}
