# AI Agent 通用底座框架 — 设计文档

日期：2026-10-30
状态：待评审

---

## 1. 意图与背景

要做一个**可复用的 Java 模块**，作为「AI 客服 / AI Agent」的通用底层底座。以后每个新项目只要把这个底座引进来，就能快速搭出带 agent 管理、skill/技能、知识库（RAG）能力的应用，不用再重复造轮子。团队只迭代这一个底座。

**它是谁、谁用它：**

- 第一消费场景是 AI 客服，但模块本身保持**通用**，不把客服业务逻辑写进底座。
- 使用方是 Java 微服务团队，已有的微服务栈基于 Spring Boot。
- 底座自己就是 agent 运行时，**直接调用大模型 API**（不是给外部 AI 提供能力的服务）。
- 个人项目，命名用占位符，不绑定公司域名。

## 2. 目标 / 非目标 / 反目标

### 目标（Goals）

1. **可复用 + 双形态部署**：核心库可被单体项目内嵌（in-process 调），也可打包成 Spring Boot 服务壳部署，微服务通过 HTTP 调用。
2. **agent 引擎**：多 agent（配置级）管理；agent loop（对话/任务循环）+ tool-calling + 流式输出；预留子 agent 编排扩展点。
3. **skill 系统**：skill = 提示词 + 打包好的工具/脚本的组合体；tool = 底层原子函数。
4. **RAG 知识库**：清洗 → 分片分段 → 向量化 → 建索引 → 写向量库 → 按 chunkId 增量更新知识片段。
5. **SPI 抽象，换实现不换代码**：`ChatModel`（换模型）、`VectorStore`（换向量库）、`EmbeddingModel`（换向量化模型）、`Authenticator`（换鉴权）、`Tool`、`ConfigRepository`（换配置存储）、`ResiliencePolicy`（换弹性策略）。
6. **多方式定义工具**：`Tool` 接口、`AbstractTool` 抽象基类、`@Tool` 注解、声明式 HTTP 工具，统一注册。
7. **配置存数据库**：agent/skill/声明式工具等声明式配置存 DB，用户可后续修改、可热重载。
8. **弹性与预防**：超时、重试、熔断、降级（默认抽象类，用户实现降级逻辑）+ 预防处理（限流、校验、token 预算、审计等）。
9. **文档齐全**：接口、注解、YAML 字段都有清晰定义，方便他人查阅理解。

### 非目标（Non-goals，第一版不做）

- 可视化管理后台 UI（v1 只提供 REST 管理 API + 配置 + 代码，UI 后续再说）。
- 模型训练 / 微调。
- 完整的子 agent 编排（v1 只做配置级多 agent + 预留编排扩展点）。
- 对话记忆的长期持久化与多租户精细化计费。

### 反目标（Anti-goals，做成这样算失败）

- 核心库强依赖 Spring——那样单体引 jar 就被绑死 Web 框架。
- 每个模型 / 每个向量库各写一套调用逻辑——抽象泄漏。
- 客服业务逻辑泄漏进核心库。
- 弹性逻辑散落各处、每个调用点各写一份——必须有统一的策略入口。

## 3. 约束

- **Java 17+ 最低基线**（能跑在 17 及以上所有 LTS；Spring Boot 3.x 的最低要求）。
- **核心库零 Spring 依赖**；Spring Boot 只出现在 `server` 服务壳里。
- **Maven 多模块**工程。
- 模型优先 **DeepSeek / 通义千问 / 本地 Ollama**（三者基本都兼容 OpenAI 接口格式）。
- 向量库优先 **Elasticsearch + Redis**。
- **配置存数据库**（声明式配置），代码注册的工具按 `name` 引用。
- 个人项目，groupId/artifactId 用占位名，不绑定公司域名。

## 4. 方案取舍（Approaches）

| 决策点 | 选定的方案 | 取舍过的其它方案 | 理由 |
|---|---|---|---|
| 部署形态 | 核心库 + 服务壳（分层） | 纯独立服务 / 纯 jar 依赖 | 微服务部署壳、单体引 jar，迭代同一套核心 |
| 模型接入 | 一个「OpenAI 兼容」适配器覆盖 DeepSeek/千问/Ollama | 每家各写原生适配器 | 三家都兼容 OpenAI 接口，一个适配器省三份活；原生适配器后续按需补 |
| 工具定义 | 四路并存，统一 registry | 只做声明式配置 / 只做 Java 接口 | 声明式 HTTP 覆盖「调微服务」80% 场景；接口/基类/注解覆盖复杂逻辑与已有 Service 方法 |
| 向量库适配 | SPI + 能力标志 | 每个库一套专属查询逻辑 | 用上各库长处（如混合检索），但不抽象泄漏 |
| 多 agent | 配置级打底 + 编排预留 | 一步到位编排 | 客服起步配置级足够；编排复杂度高，先留扩展点 |
| 配置存储 | `ConfigRepository` SPI + JDBC 实现（存 DB） | 文件 / 代码硬编码 | 用户后续在 DB 改配置即可，可热重载 |
| 弹性 | 内置轻量实现（`AbstractResiliencePolicy`），SPI 可替换 | 直接引入 Resilience4j | 保持核心轻、零重量级依赖；SPI 兜底可换 |
| 版本基线 | Java 17 最低 | Java 21 | 适配最低及以上版本，随版本平滑迭代 |

## 5. 模块结构

```
ai-agent-framework/
├── core/         引擎核心：Agent、Skill、Tool SPI + registry、AgentLoop、会话/流式、
│                 ConfigRepository SPI、ResiliencePolicy SPI、Guard 链、编排扩展点（零 Spring）
├── core-llm/     ChatModel SPI、ToolCall/ToolResult 数据模型、
│                 OpenAI 兼容适配器（后续补原生适配器）
├── core-rag/     RAG 管道：清洗/分片、EmbeddingModel SPI、VectorStore SPI、
│                 索引/检索/按 chunkId 增量更新
├── tools-http/   声明式 HTTP 工具 + Authenticator SPI（静态 header / JWT / 签名 / OAuth2 / 自定义）
├── config-jdbc/  ConfigRepository 的 JDBC 实现（MySQL / PostgreSQL）
└── server/       Spring Boot 服务壳：REST 管理接口、SSE 流式端点、装配上述模块
```

**依赖方向（粗粒度）**：`core` 依赖 `core-llm`（AgentLoop 需要 `ChatModel`）；`core-rag`、`tools-http`、`config-jdbc` 相对独立、通过 SPI 接入 `core`；`server` 聚合所有模块。具体包名、异常体系、日志框架由构建者定。

## 6. 关键接口定义（SPI）

> 这一节是「把定义写清楚」的核心，供他人查阅理解。方法签名仅示意，最终以代码为准。

### 6.1 ChatModel（模型抽象）

```java
public interface ChatModel {
    ChatResponse chat(ChatRequest request);          // 非流式
    Stream<ChatChunk> stream(ChatRequest request);   // 流式
    ChatModelCapabilities capabilities();            // 能力标志
}
```

`ChatRequest` 含 `messages`、`tools`（`ToolDefinition`）、`config`（temperature/maxTokens）。`ToolDefinition` = `name` + `description` + `parameters`（JSON Schema）。

### 6.2 Tool（原子工具，四路定义统一到这层）

```java
public interface Tool {
    String name();
    String description();
    ToolParameters parameters();                     // JSON Schema
    ToolResult execute(ToolInvocation invocation);
}

public abstract class AbstractTool implements Tool {
    // 框架提供默认实现：参数校验、超时、重试、日志、异常转 ToolResult
    protected abstract ToolResult doExecute(ToolInvocation invocation);
}
```

注解方式（反射生成 `ToolDefinition`，可标注在已有 `@Service` 方法上）：

```java
@Tool(name = "query_order", description = "根据订单号查询订单状态和物流")
public class OrderTool {
    @ToolMethod
    public Order queryOrder(@ToolParam(description = "订单号", required = true) String orderId) { ... }
}
```

声明式 HTTP 工具（见 §7）。所有方式最终都注册进统一的 `ToolRegistry`，可绑定到 agent 或打包进 skill。

### 6.3 Skill（技能包）

```java
public interface Skill {
    String name();
    String description();       // 触发/匹配描述，供模型判断何时用
    String instructions();      // 给模型的指令（技能说明书）
    List<Tool> tools();         // 打包的工具
    Map<String, Object> resources();  // 附带的脚本/资源
}
```

### 6.4 Agent（配置级多 agent + 编排扩展点）

```java
public interface Agent {
    String id();
    String systemPrompt();
    ChatModel model();
    List<Tool> tools();
    List<Skill> skills();
    // 编排扩展点（v1 预留，不实现委派逻辑）：
    List<Agent> subAgents();
    DelegationPolicy delegationPolicy();   // 预留接口
}
```

### 6.5 ConfigRepository（配置存储抽象，存 DB）

```java
public interface ConfigRepository {
    List<AgentConfig> loadAgents();
    List<SkillConfig> loadSkills();
    List<HttpToolConfig> loadHttpTools();
    void saveAgent(AgentConfig config);      // 用户后续修改
    void deleteAgent(String id);
    void subscribe(ConfigChangeListener listener);  // 变更监听 → 热重载
}
```

- 默认实现 `InMemoryConfigRepository`（测试 / 内嵌）；`JdbcConfigRepository`（MySQL / PostgreSQL，放 `config-jdbc`）。
- **存储边界**：声明式配置（agent 定义、skill 的 instructions、声明式 HTTP 工具、模型参数、降级规则）存 DB；代码注册的工具仍在代码里，DB 配置按 `name` 引用。

### 6.6 ResiliencePolicy（弹性：超时/重试/熔断/降级）

```java
public interface ResiliencePolicy {
    <T> T execute(ResilienceContext ctx, ResilienceCallable<T> action);
}

public abstract class AbstractResiliencePolicy implements ResiliencePolicy {
    protected Duration timeout() { return Duration.ofSeconds(30); }        // 默认超时
    protected int maxRetries() { return 2; }                              // 默认重试
    protected int circuitBreakerThreshold() { return 5; }                 // 连续失败熔断
    protected Duration circuitBreakerHalfOpenAfter() { return Duration.ofSeconds(30); } // 半开探测
    // 用户必须实现：降级
    protected abstract <T> T fallback(ResilienceContext ctx, Throwable cause);
}
```

- 内置默认实现覆盖「超时 + 重试 + 熔断 + 半开探测」，用户只需实现 `fallback(...)` 决定降级行为。
- **降级示例（客服场景）**：LLM 请求超时 → 返回兜底文案「抱歉，当前咨询繁忙，请稍后再试」；或降级到备用模型 / 走本地规则匹配。
- **应用层次**：LLM 调用、工具调用、HTTP 工具、向量库检索，都支持挂 `ResiliencePolicy`。
- `ResiliencePolicy` 是 SPI，用户后续可整体替换成 Resilience4j 等更重实现。

### 6.7 Guard（预防处理，请求前/后拦截链）

```java
public interface Guard {
    void before(GuardContext ctx);
    void after(GuardContext ctx, Object result, Throwable error);
}
```

内置 Guard：限流（令牌桶/滑动窗口）、输入校验/敏感词过滤、token 预算控制、并发上限、幂等、审计日志。用户可自定义扩展，按序组成链。

### 6.8 VectorStore（向量库抽象 + 能力标志）

```java
public interface VectorStore {
    void upsert(List<VectorRecord> records);
    List<SearchHit> search(VectorQuery query);
    void delete(List<String> ids);                       // 按 id 删
    void update(List<VectorRecord> records);             // 按 chunkId 增量更新
    Set<VectorStoreCapability> capabilities();           // HYBRID_SEARCH / METADATA_FILTER / BATCH_UPSERT ...
}
```

### 6.9 EmbeddingModel（向量化模型抽象）

```java
public interface EmbeddingModel {
    float[] embed(String text);
    int dimension();
}
```

### 6.10 Authenticator（HTTP 工具鉴权抽象）

```java
public interface Authenticator {
    Map<String, String> applyHeaders(Map<String, Object> context);
    // 内置实现：StaticHeader、Jwt（HS256/RS256）、HmacSignature、OAuth2ClientCredentials、自定义
}
```

## 7. 声明式 HTTP 工具配置示例（含鉴权）

```yaml
tools:
  - name: query_order
    description: 根据订单号查询订单状态和物流信息
    parameters:
      orderId:
        type: string
        description: 订单号
        required: true
    http:
      method: POST
      url: "http://order-service/internal/queryOrder"
      auth:
        type: jwt                          # 或 header / hmac / oauth2
        algorithm: HS256
        secretRef: "${secret:ORDER_JWT_KEY}"
        claims: { iss: "ai-agent", expInSec: 60 }
      bodyTemplate: '{"orderId": "{{orderId}}"}'
      extract: "$.data"                    # JsonPath，抠出回喂给模型的部分
      timeoutMs: 3000
```

框架启动时自动：① 生成给模型的 `ToolDefinition`；② 模型调用时按配置发请求（`{{orderId}}` 填参、`auth` 自动加鉴权头）；③ 用 `extract` 裁剪响应后回喂模型。

## 8. 会话与流式

- `ConversationState`：会话消息历史 + 上下文窗口管理（截断/摘要策略，v1 用简单截断）。
- 流式输出：核心库用回调 / `Stream` 接口；服务壳层转成 **SSE** 暴露。

## 9. 文档与使用说明

- 每个接口/注解/YAML 字段的 **javadoc**。
- `docs/` 使用指南：快速开始、工具四路定义写法、Skill 编写规范、YAML 配置参考、弹性策略与降级写法、如何新增模型/向量库/配置存储适配器。

## 10. 留给构建者（builder 的自行决定项）

- 具体 Maven coordinates（groupId/artifactId，占位名）、Java 包名。
- 异常体系、日志框架选型、线程模型（是否启用虚拟线程）。
- `AgentLoop` 内部实现细节（tool-calling 循环、重试、超时）。
- 会话持久化的具体实现（v1 可内存 / 可选外接存储）。
- `config-jdbc` 具体用 JDBC 直连还是 MyBatis/JPA（保持核心零 Spring，DB 实现放独立模块即可）。

## 11. 第一版里程碑（建议顺序）

1. **M1**：`core` + `core-llm`，`ChatModel` SPI + OpenAI 兼容适配器，跑通 DeepSeek/千问/Ollama 的基础 AgentLoop + `Tool` 接口。
2. **M2**：`skill` 系统 + 四路工具定义 + `tools-http`（含鉴权）。
3. **M3**：`core-rag` + `VectorStore` SPI + Elasticsearch/Redis 实现 + 增量更新。
4. **M4**：`config-jdbc` + `ConfigRepository` 存 DB + 热重载；`ResiliencePolicy` + `Guard` 链。
5. **M5**：`server` 服务壳 + REST 管理接口 + SSE 流式。
6. **M6**：编排扩展点 + 文档补全。

## 12. 已确认决策（评审结论）

1. **配置库用 MySQL**（优先支持；PostgreSQL 作为后续可选，语法基本通用）。
2. **弹性默认实现走轻量内置**（超时+重试+熔断+降级抽象类），不引 Resilience4j，`ResiliencePolicy` SPI 留替换口。
3. v1 不做管理后台 UI；个人项目，groupId/artifactId 用占位名。

**测试环境**：飞牛 OS（fnOS）`192.168.5.15:5666`，已有 MySQL 和 Redis 服务，可安装新服务；账号信息见项目根目录 `通用信息.txt`（敏感，不入库）。
