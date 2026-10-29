# M1：AgentLoop + ChatModel SPI + OpenAI 兼容适配器 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: 使用 superpowers:executing-plans（inline 模式，本会话直接实现）。步骤用 `- [ ]` 勾选跟踪。

**Goal:** 跑通一个最小的 agent 运行时：`ChatModel` SPI + OpenAI 兼容适配器（覆盖 DeepSeek/千问/Ollama）+ `Tool` 接口 + `AgentLoop`（带 tool-calling 的对话循环）。

**Architecture:** Maven 多模块，`core-llm` 定义模型抽象与 OpenAI 兼容适配器，`core` 定义 `Tool` SPI / `ToolRegistry` / `AgentLoop`，`core` 依赖 `core-llm`。核心零 Spring 依赖，HTTP 用 JDK 内置 `HttpClient`，JSON 用 Jackson。

**Tech Stack:** Java 17（JDK 21 编译、`--release 17`）、Maven 3.9.16、JUnit 5、Jackson `jackson-databind`、JDK 内置 `HttpClient` 与 `com.sun.net.httpserver.HttpServer`（测试用 mock）。

**Spec:** `docs/superpowers/specs/2026-10-30-ai-agent-framework-design.md`（§4、§5、§6.1、§6.2、§11 M1）

## Global Constraints

- Java 版本基线 **17**（`maven.compiler.release=17`）。
- 核心模块**零 Spring 依赖**。
- 构建命令统一用本机 Maven：`E:\claudePoject\tools\apache-maven-3.9.16\bin\mvn`（下文简称 `mvn`）。
- 模块坐标：父 `com.aia:ai-agent-framework:0.1.0-SNAPSHOT`；子 `com.aia:aia-core-llm`、`com.aia:aia-core`。
- 包名：`com.aia.llm`（core-llm）、`com.aia.core`（core）。
- 流式输出 `stream()` **不在 M1 实现**，延后到 server/SSE 里程碑。

## Review Focus

| # | 输入 / 失效情形 | 合理期望 | 覆盖测试 |
|---|---|---|---|
| 1 | 模型返回非 200（如 500 带 `{"error":{"message":"..."}}`） | 适配器抛 `ChatModelException`（含状态码），不解析崩溃 | Task 3 测试 D |
| 2 | 模型返回 `content: null` 且无 `tool_calls` | `AgentLoop` 返回空串，不抛 NPE | Task 5 测试 E |
| 3 | 模型要调的工具未注册 | 回喂 `ToolResult.error("未找到工具: <name>")`，循环继续 | Task 5 测试 B |
| 4 | 工具执行抛异常 | 捕获转成 `ToolResult.error`，循环继续，不整体崩溃 | Task 5 测试 C |
| 5 | 循环达到最大迭代次数仍无最终答案 | 抛 `IllegalStateException`，不无限循环 | Task 5 测试 D |

---

### Task 1: Maven 多模块骨架

**Files:**
- Create: `pom.xml`（父，packaging=pom，`<modules>` 含 core-llm、core，`<dependencyManagement>` 管理 Jackson/JUnit 版本，`maven.compiler.release=17`，surefire 插件）
- Create: `core-llm/pom.xml`（依赖 jackson-databind）
- Create: `core/pom.xml`（依赖 `com.aia:aia-core-llm`）
- Create: `.gitignore`（忽略 `target/`、`.idea/`、`*.iml`）

**Interfaces:**
- Produces: 父工程与两个空模块，`mvn compile` 能通过。

- [ ] **Step 1: git init**

```bash
cd "E:\claudePoject\通用ai客服项目" && git init -b main
```

- [ ] **Step 2: 创建父 pom 与两个子模块 pom**

父 `pom.xml` 关键内容：`<groupId>com.aia</groupId>`、`<artifactId>ai-agent-framework</artifactId>`、`<version>0.1.0-SNAPSHOT</version>`、`<packaging>pom</packaging>`；`<properties>` 设 `maven.compiler.release=17`、`jackson.version=2.17.2`、`junit.version=5.10.2`；`<dependencyManagement>` 声明 `jackson-databind` 与 `junit-jupiter`（scope test）；`<build>` 挂 `maven-surefire-plugin:3.2.5`。`core-llm/pom.xml` 父为上述，artifactId `aia-core-llm`，依赖 `jackson-databind`。`core/pom.xml` 父同上，artifactId `aia-core`，依赖 `com.aia:aia-core-llm:${project.version}`。

- [ ] **Step 3: 验证编译通过**

Run: `mvn -q compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 4: 提交**

```bash
git add pom.xml core-llm/pom.xml core/pom.xml .gitignore
git commit -m "chore: init maven multi-module skeleton (core, core-llm)"
```

---

### Task 2: core-llm 数据模型 + ChatModel 接口

**Files:**
- Create: `core-llm/src/main/java/com/aia/llm/ChatMessageRole.java`
- Create: `core-llm/src/main/java/com/aia/llm/ToolCall.java`
- Create: `core-llm/src/main/java/com/aia/llm/ChatMessage.java`
- Create: `core-llm/src/main/java/com/aia/llm/ToolDefinition.java`
- Create: `core-llm/src/main/java/com/aia/llm/ChatModelConfig.java`
- Create: `core-llm/src/main/java/com/aia/llm/ChatRequest.java`
- Create: `core-llm/src/main/java/com/aia/llm/Usage.java`
- Create: `core-llm/src/main/java/com/aia/llm/ChatResponse.java`
- Create: `core-llm/src/main/java/com/aia/llm/ChatModelCapability.java`
- Create: `core-llm/src/main/java/com/aia/llm/ChatModel.java`
- Test: `core-llm/src/test/java/com/aia/llm/ChatMessageTest.java`

**Interfaces:**
- Produces（后续任务依赖的精确签名）:

```java
public enum ChatMessageRole { SYSTEM, USER, ASSISTANT, TOOL }

public record ToolCall(String id, String name, Map<String,Object> arguments) {}

public record ChatMessage(
    ChatMessageRole role,
    String content,               // 文本；TOOL 消息为工具结果；可为 null
    List<ToolCall> toolCalls,     // ASSISTANT 的 tool_calls，通常为空表
    String toolCallId             // TOOL 消息对应的 tool_call id，非 TOOL 为 null
) {
    public static ChatMessage system(String content)
    public static ChatMessage user(String content)
    public static ChatMessage assistant(String content)
    public static ChatMessage assistantWithToolCalls(List<ToolCall> calls)
    public static ChatMessage tool(String toolCallId, String content)
}

public record ToolDefinition(String name, String description, Map<String,Object> parameters) {}

public record ChatModelConfig(Double temperature, Integer maxTokens) {}

public record ChatRequest(List<ChatMessage> messages, List<ToolDefinition> tools, ChatModelConfig config) {}

public record Usage(Integer promptTokens, Integer completionTokens) {}

public record ChatResponse(ChatMessage message, String finishReason, Usage usage) {}

public enum ChatModelCapability { TOOL_CALLING }

public interface ChatModel {
    ChatResponse chat(ChatRequest request);
    Set<ChatModelCapability> capabilities();
}
```

- [ ] **Step 1: 写失败测试**

`ChatMessageTest`：
- `systemFactorySetsRoleAndContent`：`ChatMessage.system("x")` → `role()==SYSTEM`、`content()=="x"`、`toolCalls()` 为空、`toolCallId()==null`。
- `assistantWithToolCallsKeepsCalls`：给定 `List.of(new ToolCall("c1","f",Map.of("k",1)))` → `role()==ASSISTANT`、`toolCalls()` 大小 1、`content()==null`。
- `toolFactorySetsToolCallId`：`ChatMessage.tool("c1","res")` → `role()==TOOL`、`toolCallId()=="c1"`、`content()=="res"`。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl core-llm test -Dtest=ChatMessageTest`
Expected: 编译失败（类型不存在）。

- [ ] **Step 3: 实现上述 10 个类型**

按 Interfaces 块的精确签名创建；工厂方法用 `List.of()` 做空表默认值。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl core-llm test -Dtest=ChatMessageTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add core-llm
git commit -m "feat(core-llm): add ChatModel SPI and message data model"
```

---

### Task 3: OpenAI 兼容适配器

**Files:**
- Create: `core-llm/src/main/java/com/aia/llm/openai/ChatModelException.java`
- Create: `core-llm/src/main/java/com/aia/llm/openai/OpenAiCompatibleChatModel.java`
- Test: `core-llm/src/test/java/com/aia/llm/openai/OpenAiCompatibleChatModelTest.java`

**Interfaces:**
- Consumes: Task 2 的全部类型。
- Produces:

```java
public class ChatModelException extends RuntimeException {
    public ChatModelException(String message, int statusCode)
    public int statusCode()
}

public final class OpenAiCompatibleChatModel implements ChatModel {
    // baseUrl 含 /v1 前缀，末尾自动拼 /chat/completions；
    // 示例：DeepSeek https://api.deepseek.com/v1、
    //       Qwen https://dashscope.aliyuncs.com/compatible-mode/v1、
    //       Ollama http://localhost:11434/v1
    public OpenAiCompatibleChatModel(String baseUrl, String apiKey, String model, HttpClient httpClient)
    public ChatResponse chat(ChatRequest request)
    public Set<ChatModelCapability> capabilities()  // 返回 {TOOL_CALLING}
}
```

- [ ] **Step 1: 写失败测试（用 JDK `HttpServer` 作 mock）**

`OpenAiCompatibleChatModelTest` 用 `@BeforeEach` 起一个 `HttpServer`（随机端口），`@AfterEach` 停止。四个测试：

- `parsesTextResponse`：handler 返回
  ```json
  {"choices":[{"message":{"role":"assistant","content":"你好，有什么可以帮你？"},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":8}}
  ```
  断言 `response.message().content().equals("你好，有什么可以帮你？")`、`response.finishReason().equals("stop")`、`response.message().role()==ASSISTANT`。
- `parsesToolCallResponse`：handler 返回
  ```json
  {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"query_order","arguments":"{\"orderId\":\"123\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":20,"completion_tokens":10}}
  ```
  断言 `toolCalls()` 大小 1，且 `id()=="call_1"`、`name()=="query_order"`、`arguments().equals(Map.of("orderId","123"))`。
- `sendsCorrectRequest`：handler 把请求体存到 `AtomicReference<String>`；调用 `chat` 后断言：请求方法 POST、路径 `/chat/completions`、头 `Authorization == "Bearer test-key"`、请求体 JSON 含 `"model":"test-model"`、`messages` 含 system 与 user、`tools` 含 `query_order` 及其 `parameters`。
- `throwsOnNon200`：handler 返回状态码 500、体 `{"error":{"message":"boom"}}`；断言 `chat` 抛 `ChatModelException` 且 `statusCode()==500`。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl core-llm test -Dtest=OpenAiCompatibleChatModelTest`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现 `ChatModelException` 与 `OpenAiCompatibleChatModel`**

用 Jackson `ObjectMapper` 序列化请求、反序列化响应；`function.arguments`（JSON 字符串）反序列化成 `Map<String,Object>`；非 200 抛 `ChatModelException`。`capabilities()` 返回 `Set.of(TOOL_CALLING)`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl core-llm test -Dtest=OpenAiCompatibleChatModelTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add core-llm
git commit -m "feat(core-llm): add OpenAI-compatible chat model adapter"
```

---

### Task 4: core 的 Tool SPI + ToolRegistry

**Files:**
- Create: `core/src/main/java/com/aia/core/Tool.java`
- Create: `core/src/main/java/com/aia/core/ToolInvocation.java`
- Create: `core/src/main/java/com/aia/core/ToolResult.java`
- Create: `core/src/main/java/com/aia/core/ToolRegistry.java`
- Test: `core/src/test/java/com/aia/core/ToolRegistryTest.java`

**Interfaces:**
- Consumes: Task 2 的 `ToolDefinition`。
- Produces:

```java
public interface Tool {
    String name();
    String description();
    Map<String,Object> parameters();            // JSON Schema
    ToolResult execute(ToolInvocation invocation);
}

public record ToolInvocation(String toolCallId, String name, Map<String,Object> arguments) {}

public record ToolResult(String content, boolean isError) {
    public static ToolResult ok(String content)
    public static ToolResult error(String content)
}

public final class ToolRegistry {
    public void register(Tool tool)              // 重复 name 抛 IllegalArgumentException
    public Optional<Tool> get(String name)
    public List<ToolDefinition> toToolDefinitions()
}
```

- [ ] **Step 1: 写失败测试**

`ToolRegistryTest`：
- `registerAndGet`：注册一个 tool 后 `get(name).isPresent()` 为真，取回的是同一实例。
- `duplicateNameThrows`：同 name 注册两次抛 `IllegalArgumentException`。
- `toToolDefinitionsConverts`：注册 name=`query_order`、description=`d`、parameters=`Map.of("orderId", Map.of("type","string"))` 的 tool，断言 `toToolDefinitions()` 得到 1 个 `ToolDefinition`，其 `name()`/`description()`/`parameters()` 均一致。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl core test -Dtest=ToolRegistryTest`
Expected: 编译失败。

- [ ] **Step 3: 实现 4 个类型**

`toToolDefinitions()` 遍历已注册工具，映射成 `ToolDefinition(name(), description(), parameters())`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl core test -Dtest=ToolRegistryTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add core
git commit -m "feat(core): add Tool SPI and ToolRegistry"
```

---

### Task 5: AgentLoop（带 tool-calling 的对话循环）

**Files:**
- Create: `core/src/main/java/com/aia/core/AgentLoop.java`
- Test: `core/src/test/java/com/aia/core/AgentLoopTest.java`

**Interfaces:**
- Consumes: Task 2（`ChatModel`/`ChatRequest`/`ChatMessage`/`ToolCall`）、Task 4（`ToolRegistry`/`ToolResult`/`ToolInvocation`）。
- Produces:

```java
public final class AgentLoop {
    public AgentLoop(ChatModel model, ToolRegistry tools, String systemPrompt)  // maxIterations 默认 10
    public AgentLoop(ChatModel model, ToolRegistry tools, String systemPrompt, int maxIterations)
    public String run(String userMessage)   // 返回最终回答；达到最大迭代抛 IllegalStateException
}
```

**算法（实现体唯一正确的写法，其余实现者可自定）：**
1. `messages = [system(systemPrompt), user(userMessage)]`。
2. 循环（最多 `maxIterations` 次）：`resp = model.chat(new ChatRequest(messages, tools.toToolDefinitions(), config))`；`msg = resp.message()`。
3. 若 `msg.toolCalls()` 为空 → 返回 `msg.content() == null ? "" : msg.content()`。
4. 否则把 `msg` 追加进 `messages`；对每个 `toolCall`：`tool = tools.get(name)`；`result = tool 存在 ? tool.execute(...) : ToolResult.error("未找到工具: "+name)`；`execute` 抛异常则捕获为 `ToolResult.error(e.getMessage())`；追加 `ChatMessage.tool(call.id(), result.content())`。
5. 循环耗尽 → 抛 `IllegalStateException("AgentLoop 达到最大迭代次数: " + maxIterations)`。

- [ ] **Step 1: 写失败测试**

`AgentLoopTest` 用一个脚本化 `FakeChatModel`（实现 `ChatModel`，按序返回预置 `ChatResponse`，并记录每次收到的 `ChatRequest`）和一个 `FakeTool`。五个测试：

- `runsToolThenReturnsAnswer`：`double` 工具 `execute` 返回 `ok("10")`；模型先返回 `assistantWithToolCalls(List.of(new ToolCall("c1","double",Map.of("number",5))))`，再返回 `assistant("结果是 10")`。断言：`run("把5翻倍").equals("结果是 10")`；工具被调用一次且参数 `number==5`；第二次 `ChatRequest` 的 messages 含一个 `role==TOOL`、`toolCallId()=="c1"`、`content()=="10"` 的消息。
- `missingToolFeedsError`：模型要调 `missing`，随后返回最终回答；断言第二次请求含 `content()=="未找到工具: missing"` 的 TOOL 消息，且循环正常结束。
- `toolExceptionFeedsError`：`execute` 抛 `RuntimeException("boom")`；断言第二次请求含 `content()=="boom"` 的 TOOL 消息，循环正常结束。
- `nullContentNoToolCallsReturnsEmpty`：模型返回 `content=null` 且无 toolCalls 的 assistant 消息；断言 `run(...)` 返回 `""`，不抛异常。
- `maxIterationsThrows`：用 `maxIterations=2` 构造，模型始终返回 tool call；断言抛 `IllegalStateException`。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl core test -Dtest=AgentLoopTest`
Expected: 编译失败（`AgentLoop` 不存在）。

- [ ] **Step 3: 实现 `AgentLoop`**

按算法块实现；用 `ChatModelConfig(null, null)` 作为默认配置。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl core test -Dtest=AgentLoopTest`
Expected: PASS

- [ ] **Step 5: 全量测试 + 提交**

```bash
mvn -q test
git add core
git commit -m "feat(core): add AgentLoop with tool-calling loop"
```

Expected: 全部模块测试 PASS。
