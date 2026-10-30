# 接入指南

把这套框架引入到你自己的项目里。**先选接入形态，再按需加能力。**

---

## 1. 先选形态

| 形态 | 适用 | 依赖 | 代价 |
|---|---|---|---|
| **单体引 jar** | 已有 Spring Boot 项目想加个 AI 功能 | `aia-core` + `aia-core-llm` | 无额外进程，随你的服务一起发布 |
| **独立服务** | 多个项目要共用一套 AI 能力 | `server` 模块打出来的 jar | 多一个服务要运维，调用方改走 HTTP |
| **混合** | 核心逻辑在单体内，知识库/多 agent 独立 | 两者都引 | 最灵活，也最复杂 |

> 建议：**先按单体引 jar 起步**。等知识库要独立扩容、或者多个团队要共用时，再把 `server` 部署出去。
> 因为 `core` 系列模块零 Spring 依赖，从单体迁到独立服务时业务代码基本不用改。

---

## 2. 模块说明

| 模块 | 内容 | 什么时候需要 |
|---|---|---|
| `aia-core-llm` | `ChatModel` SPI + OpenAI 兼容适配器 + 供应商预设 | **必选** |
| `aia-core` | Agent 循环、工具、技能、会话、压缩、路由、编排、防护 | **必选** |
| `aia-core-rag` | 分片、向量化、向量库（ES/Redis/内存）、重排、检索缓存 | 需要知识库时 |
| `aia-tools-http` | 声明式 HTTP 工具 + JWT/HMAC 鉴权 | 要调微服务/第三方接口时 |
| `aia-config-jdbc` | agent/skill 配置存数据库 | 要动态改配置时 |
| `aia-session-store` | 会话与答案缓存的 Redis/JDBC 实现 | 要多轮会话且要持久化时 |
| `aia-server` | Spring Boot 服务壳 + REST 接口 | 要独立部署时 |

Maven 坐标（先 `mvn install` 装到本地仓库）：

```xml
<dependency>
    <groupId>com.aia</groupId>
    <artifactId>aia-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

---

## 3. 五分钟跑通（不依赖 Spring）

完整代码见 [`examples/minimal-agent`](../examples/minimal-agent)。

```java
// 1. 选模型：任何 OpenAI 兼容服务都能用
ChatModel model = new OpenAiCompatibleChatModel(
        "https://api.deepseek.com/v1", apiKey, "deepseek-chat", HttpClient.newHttpClient());

// 2. 定义工具（注解方式，方法名/参数自动变成 function calling 的 schema）
public class OrderTools {
    @ToolDef(name = "query_order", description = "根据订单号查询物流状态")
    public String queryOrder(@ToolParam(description = "订单号", required = true) String orderId) {
        return "订单 " + orderId + " 已发货，预计明天送达";
    }
}

// 3. 注册 + 跑
ToolRegistry tools = new ToolRegistry();
new ToolScanner().scan(new OrderTools()).forEach(tools::register);

AgentLoop loop = new AgentLoop(model, tools, "你是电商客服，查订单必须先调用 query_order。");
System.out.println(loop.run("订单 A123 到哪了"));
```

模型会自己决定调用 `query_order`、拿到结果、再组织语言回答。**整个过程你不用写 if/else 判断意图。**

---

## 4. 按需加能力

### 4.1 加知识库（RAG）

```java
EmbeddingModel embedder = new OpenAiCompatibleEmbeddingModel(
        "https://api.siliconflow.cn/v1", key, "Qwen/Qwen3-Embedding-0.6B", 1024, httpClient);

VectorStore store = new InMemoryVectorStore(embedder.dimension());   // 生产换 ES / Redis
KnowledgeBase kb = new KnowledgeBase(embedder, store, new TextChunker(), 500, 50,
        null,                                    // 重排模型，可空
        new InMemoryRetrievalCache(2000, 300));  // 检索缓存

kb.addDocument("refund", "退款政策：签收后 7 天内可无理由退款……");

// 把检索包装成工具，agent 就会自己决定什么时候查
tools.register(new KnowledgeSearchTool(kb, 5));
```

**为什么用 `KnowledgeSearchTool` 而不是直接 `kb.search`**：前者让模型自己判断要不要查。
寒暄类问题不会触发检索，省一次调用。

### 4.2 加多轮会话

```java
SessionSupport support = new SessionSupport(
        new InMemorySessionStore(),                       // 会话
        new InMemoryAnswerCache(500),                     // 答案缓存，可空
        new RuleBasedRetrievalRouter("您好，请问有什么可以帮您？", List.of()),  // 检索路由
        new SummarizingContextCompressor(model, 3000, 8));// 上下文压缩

AgentRuntime runtime = new AgentRuntime(repo, tools, name -> model,
        new DefaultToolResolver(), support);

runtime.chat("agent-1", "session-1", "我被人辞退了");     // 第二次自动带上历史
```

### 4.3 换模型供应商

```java
ProviderPreset preset = Providers.require("dashscope");   // deepseek/dashscope/siliconflow/openai/ollama
ChatModel model = new OpenAiCompatibleChatModel(
        preset.baseUrl(), apiKey, preset.chatModel(), httpClient);

// 自定义供应商
Providers.register(new ProviderPreset("my-gateway", "https://llm.my.com/v1",
        "gpt-4o", "bge-m3", 1024, "bge-reranker-v2-m3"));
```

### 4.4 调公司内部微服务

零代码，配置化：

```java
HttpToolConfig cfg = new HttpToolConfig(
        "query_order", "查订单", Map.of("orderId", Map.of("type", "string")),
        "POST", "http://order-service/internal/queryOrder",
        Map.of(), "{\"orderId\":\"{{orderId}}\"}", "$.data", 3000,
        new JwtAuthenticator("HS256", "secret", Map.of("iss", "ai-agent"), 60));
tools.register(new HttpTool(cfg));
```

鉴权可选 `StaticHeaderAuthenticator` / `JwtAuthenticator`(HS256/RS256) / `HmacSignatureAuthenticator`。

### 4.5 多 agent 分派

接待 agent 收集信息 → 按内容分派给专业 agent：

```java
ScenarioSupport 里配上 ScenarioRouter（见 4.2 的 SessionSupport 第 5 个参数）
```

分派是**确定性**的：必填槽位齐了框架直接接管，不依赖模型主动调工具。

### 4.6 加并发防护

```java
GuardChain guards = new GuardChain(List.of(
        new RateLimitGuard(100),                 // 每秒最多 100 个请求
        new ConcurrencyGuard("模型", 32, 2000))); // 同时最多 32 个，排队超过 2 秒就 503

new AgentRuntime(repo, tools, factory, resolver, support, guards, metrics);
```

---

## 5. Spring Boot 接入

### 5.1 最小配置

```yaml
aia:
  llm:
    provider: deepseek        # deepseek/dashscope/siliconflow/openai/ollama
    api-key: ${LLM_API_KEY}
```

然后注入 `AgentRuntime` 直接用。或者把 `aia-server` 当依赖，得到现成的 REST 接口。

### 5.2 全量配置模板

见 [`docs/usage.md`](usage.md)，或 `server/src/main/resources/application.yml`（每一段都有中文注释）。

### 5.3 现成的 REST 接口

| 接口 | 说明 |
|---|---|
| `GET/POST/DELETE /api/agents` | agent 配置 CRUD |
| `GET/POST/DELETE /api/skills` | skill 配置 CRUD |
| `POST /api/chat/{agentId}` | 问答，body `{message, sessionId}` |
| `POST /api/chat/{agentId}/stream` | SSE 流式 |
| `POST /api/kb/documents` | 写入知识 |
| `POST /api/kb/search` | 直接检索（调试用） |
| `POST /api/eval/run` | 跑评测集 + 回归门禁 |
| `POST /api/eval/dialogue` | 多轮对话评测 |
| `POST /api/eval/routing` | 路由策略横向对比 |
| `POST /api/eval/cache` | 缓存收益评估 |
| `GET /api/health` | 健康检查（关闭时切 draining） |
| `GET /actuator/prometheus` | 指标 |

---

## 6. 上线检查清单

- [ ] 向量库用 **ES**（`aia.rag.store=es`）——多实例必须共用，否则各实例知识不一致
- [ ] 会话/答案缓存/检索缓存切 `composite` 或 `redis`
- [ ] `aia.concurrency.max-concurrent` 按单机承载能力设置（先用压测定）
- [ ] `aia.rag.store` 与 `aia.rag.cache.redis.*` 指向同一套 Redis 集群
- [ ] 网关接 `/api/health`，摘流量后再关进程（`server.shutdown=graceful`）
- [ ] 配 Prometheus 抓取 `/actuator/prometheus`，接上缓存命中率/过载拒绝/成本告警
- [ ] 用评测集跑一次基线，之后每次改模型或提示词都重跑

---

## 7. 踩过的坑（照这个避）

| 坑 | 现象 | 处理 |
|---|---|---|
| 每请求新建 HttpClient | 高并发下延迟高、TIME_WAIT 堆积 | 复用同一个 `HttpClient` |
| 知识库写入不清缓存 | 更新文档后仍返回旧内容 | 框架已内置，但生产要用持久化向量库 |
| 多实例各用内存向量库 | 各实例知识不一致 | 统一用 ES |
| 语义缓存复用答案 | 把 FAQ 答案套到别人案情上 | 法律/医疗这类强个性化场景**只用精确缓存** |
| 用 memory 向量库 + 启动灌数据 | 每次重启都清空共享缓存 | 生产用 ES，seeder 只在首次运行 |
| 流式请求统计不到 token | 成本统计偏低 | 流式响应无 usage 字段，改非流式或补计费接口 |
| `toolNames` 写 `[]` 以为是不限 | agent 拿不到任何工具 | `[]` = 零工具，`["*"]` 才是全部 |

---

## 8. 模块依赖关系

```
core-llm  (ChatModel / Embedding 预设)
   ↑
 core  (Agent 循环 / 工具 / 会话 / 编排 / 防护)
   ↑                    ↑
core-rag            tools-http
   ↑
config-jdbc / session-store
   ↑
server (Spring Boot 壳)
```

`core` 及其下游**零 Spring 依赖**，这也是能从单体平滑迁到独立服务的原因。
