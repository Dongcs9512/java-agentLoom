# S1：流式返回（SSE）实现计划

> **For agentic workers:** 使用 superpowers:executing-plans（inline）。

**Goal:** 端到端流式：`ChatModel.stream`（OpenAI 兼容 SSE 解析）→ `AgentLoop.stream` → 服务端 `SseEmitter` 的 SSE 接口，让客服回答逐 token 返回。

**Architecture:** `core-llm` 加 `ChatChunk` + `ChatModel.stream(request, onChunk)`（默认非流式回退）；`OpenAiCompatibleChatModel` 覆盖为真实 SSE 解析（含 tool_calls 增量累积）；`core` 加 `AgentLoop.stream`；`server` 加 `AgentRuntime.streamChat` + `POST /api/chat/{agentId}/stream`（SseEmitter）。

**Spec:** `docs/superpowers/specs/2026-10-30-ai-agent-framework-design.md`（§6.1、§8）

## Review Focus

| # | 失效情形 | 期望 | 测试 |
|---|---|---|---|
| 1 | 流式响应含 `[DONE]` 结束标记 | 正常结束，不把标记当内容 | Task 2 |
| 2 | 流式 tool_calls 参数分片到达 | 累积成完整 `ToolCall` | Task 2 |
| 3 | 流式模型只输出文本无工具 | `AgentLoop.stream` 逐段回调文本 | Task 3 |
| 4 | 流式下先工具后最终答案 | 工具执行后最终答案仍逐段回调 | Task 3 |

---

### Task 1: ChatChunk + ChatModel.stream 默认方法

**Files:** Create `core-llm/src/main/java/com/aia/llm/ChatChunk.java`；Modify `ChatModel.java`；Test `core-llm/src/test/java/com/aia/llm/ChatChunkTest.java`

**Produces:**
```java
public record ChatChunk(String content, List<ToolCall> toolCalls, String finishReason) {
    public ChatChunk(String content) { this(content, List.of(), null); }
}
public interface ChatModel {
    ChatResponse chat(ChatRequest request);
    default void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
        ChatResponse r = chat(request);
        onChunk.accept(new ChatChunk(r.message().content(), r.message().toolCalls(), r.finishReason()));
    }
    Set<ChatModelCapability> capabilities();
}
```

### Task 2: OpenAiCompatibleChatModel.stream（SSE 解析）

**Files:** Modify `core-llm/src/main/java/com/aia/llm/openai/OpenAiCompatibleChatModel.java`；Test 加 `streamsTextChunks`、`streamsAndAccumulatesToolCalls`

用 `HttpClient` 发 `stream:true` 请求，`BodyHandlers.ofLines()` 逐行解析 `data:`，处理 `[DONE]`；`delta.content` 逐段回调；`delta.tool_calls` 按 index 累积 arguments，流结束组装成 `ToolCall` 后作为一个 chunk 回调。

### Task 3: AgentLoop.stream

**Files:** Modify `core/src/main/java/com/aia/core/AgentLoop.java`；Test 加 `streamEmitsTextDeltas`、`streamHandlesToolThenText`

```java
public void stream(String userMessage, java.util.function.Consumer<String> onDelta);
```

### Task 4: server SSE 接口

**Files:** Modify `AgentRuntime.java`（加 `streamChat(agentId, message, Consumer<String>)`）、`AgentController.java`（加 `POST /api/chat/{agentId}/stream` 返回 `SseEmitter`）；Test `AgentControllerTest` 加 SSE 冒烟（可选，异步 MockMvc）
