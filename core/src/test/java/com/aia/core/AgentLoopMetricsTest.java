package com.aia.core;

import com.aia.core.obs.MetricsRecorder;
import com.aia.llm.ChatMessage;
import com.aia.llm.ChatModel;
import com.aia.llm.ChatModelCapability;
import com.aia.llm.ChatRequest;
import com.aia.llm.ChatResponse;
import com.aia.llm.Usage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentLoopMetricsTest {

    static class RecordingMetrics implements MetricsRecorder {
        final Map<String, Long> values = new HashMap<>();

        @Override
        public void count(String name, long amount, Map<String, String> tags) {
            values.merge(name + tags, amount, Long::sum);
        }
    }

    private static ChatModel model(String content, Usage usage) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return new ChatResponse(ChatMessage.assistant(content), "stop", usage);
            }

            @Override
            public Set<ChatModelCapability> capabilities() {
                return Set.of(ChatModelCapability.TOOL_CALLING);
            }
        };
    }

    @Test
    void recordsCallCountAndTokens() {
        RecordingMetrics metrics = new RecordingMetrics();
        AgentLoop loop = new AgentLoop(model("好的", new Usage(120, 35)), List.of(), List.of(),
                "sys", metrics, "deepseek-chat");

        loop.run("你好");

        assertEquals(1L, metrics.values.get("aia.llm.calls{model=deepseek-chat, stream=false}"));
        assertEquals(120L, metrics.values.get("aia.llm.tokens{model=deepseek-chat, type=prompt}"));
        assertEquals(35L, metrics.values.get("aia.llm.tokens{model=deepseek-chat, type=completion}"));
    }

    @Test
    void countsEveryIterationOfToolLoop() {
        RecordingMetrics metrics = new RecordingMetrics();
        ChatModel toolCallingModel = new ChatModel() {
            private int calls = 0;

            @Override
            public ChatResponse chat(ChatRequest request) {
                calls++;
                if (calls == 1) {
                    return new ChatResponse(
                            ChatMessage.assistantWithToolCalls(List.of(new com.aia.llm.ToolCall("c1", "noop", Map.of()))),
                            "tool_calls", new Usage(50, 10));
                }
                return new ChatResponse(ChatMessage.assistant("完成"), "stop", new Usage(80, 20));
            }

            @Override
            public Set<ChatModelCapability> capabilities() {
                return Set.of(ChatModelCapability.TOOL_CALLING);
            }
        };
        AgentLoop loop = new AgentLoop(toolCallingModel, List.of(), List.of(), "sys", metrics, "m1");

        loop.run("提问");

        assertEquals(2L, metrics.values.get("aia.llm.calls{model=m1, stream=false}"));
        assertEquals(130L, metrics.values.get("aia.llm.tokens{model=m1, type=prompt}"));
        assertEquals(30L, metrics.values.get("aia.llm.tokens{model=m1, type=completion}"));
    }

    @Test
    void missingUsageDoesNotBreakCounting() {
        RecordingMetrics metrics = new RecordingMetrics();
        AgentLoop loop = new AgentLoop(model("ok", null), List.of(), List.of(), "sys", metrics, "m2");

        loop.run("hi");

        assertEquals(1L, metrics.values.get("aia.llm.calls{model=m2, stream=false}"));
        assertEquals(null, metrics.values.get("aia.llm.tokens{model=m2, type=prompt}"));
    }
}
