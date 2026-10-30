# 使用指南

## 1. 定义工具（四种方式，统一注册进 `ToolRegistry`）

### 1.1 `Tool` 接口（最自由）

```java
Tool tool = new Tool() {
    public String name() { return "query_order"; }
    public String description() { return "查订单"; }
    public Map<String,Object> parameters() { return Map.of("orderId", Map.of("type","string")); }
    public ToolResult execute(ToolInvocation inv) {
        return ToolResult.ok("订单已发货");
    }
};
registry.register(tool);
```

### 1.2 `AbstractTool` 抽象基类

```java
public class OrderTool extends AbstractTool {
    public String name() { return "query_order"; }
    public String description() { return "查订单"; }
    protected ToolResult doExecute(ToolInvocation inv) { return ToolResult.ok("..."); }
    // 异常自动转成 ToolResult.error
}
```

### 1.3 `@ToolDef` 注解（方法级）

```java
public class OrderTools {
    @ToolDef(name = "query_order", description = "查订单")
    public String queryOrder(@ToolParam(description = "订单号", required = true) String orderId) {
        return "订单 " + orderId + " 已发货";
    }
}
new ToolScanner().scan(new OrderTools()).forEach(registry::register);
```

### 1.4 声明式 HTTP 工具（调微服务，零代码）

```java
HttpToolConfig cfg = new HttpToolConfig(
    "query_order", "查订单", Map.of("orderId", Map.of("type","string")),
    "POST", "http://order-service/internal/queryOrder",
    Map.of(), "{\"orderId\":\"{{orderId}}\"}", "$.data", 3000,
    new JwtAuthenticator("HS256", "secret", Map.of("iss","ai-agent"), 60));
registry.register(new HttpTool(cfg));
```

鉴权：`StaticHeaderAuthenticator` / `JwtAuthenticator`（HS256/RS256）/ `HmacSignatureAuthenticator`。

### 1.5 工具作用域（哪些 agent 能用哪些工具）

注册进 `ToolRegistry` 的工具是**全局池**，具体某个 agent 能看到多少，由 `AgentConfig.toolNames` 决定：

| `toolNames` | 含义 |
|---|---|
| `null` | 全部全局工具（兼容旧配置） |
| `["*"]` | 全部全局工具 |
| `[]` | 不使用任何全局工具（纯对话 agent） |
| `["query_order"]` | 白名单，只用这几个；未注册的名字静默忽略 |

agent 选中的 **skill 自带工具**始终并入，不受上面规则限制，同名的只保留一个。

需要按租户、角色或数据库动态分配时，实现 `ToolResolver` 并注入：

```java
public class TenantToolResolver implements ToolResolver {
    @Override
    public List<Tool> resolve(AgentConfig agent, List<Skill> skills, ToolRegistry registry) {
        Set<String> allowed = loadAllowedToolsForTenant(agent.id());
        return registry.all().stream().filter(t -> allowed.contains(t.name())).toList();
    }
}

new AgentRuntime(repo, registry, factory, new TenantToolResolver());
```

Spring Boot 里在自定义配置类中定义一个 `ToolResolver` Bean 即可覆盖默认策略
（`@ConditionalOnMissingBean`）。

排查作用域是否符合预期：`AgentLoop.visibleToolNames()` 返回该 agent 实际可见的工具名。

## 2. 定义 Skill（提示词 + 打包工具）

```java
Skill skill = new Skill() {
    public String name() { return "logistics"; }
    public String description() { return "物流查询"; }
    public String instructions() { return "用户问物流时，先查订单再查物流。"; }
    public List<Tool> tools() { return List.of(orderTool); }
    public Map<String,Object> resources() { return Map.of(); }
};
```

## 3. 跑一个 Agent

```java
ToolRegistry tools = new ToolRegistry();
tools.register(orderTool);

ChatModel model = new OpenAiCompatibleChatModel(
    "https://api.deepseek.com/v1", "sk-xxx", "deepseek-chat", HttpClient.newHttpClient());

AgentLoop loop = new AgentLoop(model, tools, List.of(logisticsSkill), "你是客服助手");
String answer = loop.run("我的订单 123 什么时候发货？");
```

## 4. RAG 知识库

```java
KnowledgeBase kb = new KnowledgeBase(
    embedder,                     // 实现 EmbeddingModel（向量化）
    vectorStore,                  // 见 4.1 内存 / 4.2 Elasticsearch 实现
    new TextChunker(), 500, 50);
kb.addDocument("doc1", "产品说明……");
List<SearchHit> hits = kb.search("怎么退货", 5);
kb.updateChunk("doc1#0", "新内容");   // 按 chunkId 增量更新
kb.deleteDocument("doc1");
```

### 4.1 内存实现（默认，单机测试用）

```java
VectorStore vectorStore = new InMemoryVectorStore(embedder.dimension());
```

### 4.2 Elasticsearch 实现（生产，8.x）

```java
VectorStore vectorStore = new ElasticsearchVectorStore(
    "http://192.168.5.15:9200",   // ES 地址
    "kb",                          // 索引名
    embedder.dimension(),          // 向量维度
    HttpClient.newHttpClient());
```

- 内部用 REST 直连（无 ES 官方客户端依赖），自动按维度建索引，mapping 固定为
  `text`（文本）/ `dense_vector`（cosine 相似度）/ `flattened`（metadata）。
- `upsert`/`delete` 走 `_bulk` 且带 `refresh=wait_for`，返回时文档即可被 `search` 检索。
- `capabilities()` 含 `METADATA_FILTER` + `BATCH_UPSERT`：`search` 的 `filter`
  会转成 `metadata.<key>` 的精确 `term` 过滤（`docId` 等）。
- 真实 ES 冒烟测试（默认跳过，不影响常规构建）：

```bash
mvn test -Dtest=ElasticsearchVectorStoreLiveTest \
         -Daia.es.url=http://192.168.5.15:9200 \
         -Dsurefire.failIfNoSpecifiedTests=false
```

### 4.3 Redis 实现（Redis 8+ 原生 Vector Set）

不需要 Redis Stack / RediSearch —— Redis 8.0 起内置了 `vectorset` 模块，直接用 `VADD` / `VSIM`。

```java
VectorStore vectorStore = new RedisVectorStore(
    "192.168.5.15", 6379, "password",
    "aia-kb",                     // Vector Set 的 key
    embedder.dimension());
```

- 向量以 FP32 小端二进制写入，正文与元数据存进属性（正文 key 为 `text`）。
- `search` 用 `VSIM ... WITHSCORES`，返回的是余弦相似度（1 = 完全一致）。
- 支持 `METADATA_FILTER`：`filter` 里的每个键值会翻译成 Vector Set 的 `FILTER '.docId == "refund"'`。
- 注意 `VREM` 一次只能删一个元素，`delete` 内部会逐个删除。
- 真实 Redis 冒烟测试（默认跳过）：

```bash
mvn test -Dtest=RedisVectorStoreLiveTest \
         -Daia.redis.host=192.168.5.15 -Daia.redis.password=xxx \
         -Dsurefire.failIfNoSpecifiedTests=false
```

### 4.4 向量化模型（OpenAI 兼容 /v1/embeddings）

直接用供应商预设，不用记 baseUrl 和维度：

```java
ProviderPreset preset = Providers.require("siliconflow");   // 或 dashscope / openai / ollama
EmbeddingModel embedder = new OpenAiCompatibleEmbeddingModel(
    preset.baseUrl(), "sk-xxx", preset.embeddingModel(),
    preset.embeddingDimension(), HttpClient.newHttpClient());
```

也可以手动指定：

```java
EmbeddingModel embedder = new OpenAiCompatibleEmbeddingModel(
    "https://dashscope.aliyuncs.com/compatible-mode/v1", "sk-xxx",
    "text-embedding-v3", 1024, HttpClient.newHttpClient());
```

`KnowledgeBase.addDocument` 会用 `embedBatch` 一次性向量化全部切片，避免 N 次 HTTP 往返。

### 4.5 让 agent 检索知识库

```java
KnowledgeSearchTool tool = new KnowledgeSearchTool(kb, 5);
registry.register(tool);   // 注册后所有 agent 都能调用 search_knowledge
```

工具名为 `search_knowledge`，参数 `query`（必填）、`topK`（可选），返回
「序号 + 来源 chunkId + 原文」，模型据此作答。

### 4.6 在 Spring Boot 服务里启用 RAG

`application.yml`：

```yaml
aia:
  rag:
    enabled: true                  # 唯一开关，false 时框架其余功能照常可用
    store: redis                   # memory（默认，重启丢失）/ es / redis
    embedding:
      provider: siliconflow        # 选供应商，下面三项留空即用它的预设
      base-url: ""
      api-key: sk-xxx
      model: ""
      dimension: 0
    es:
      url: http://192.168.5.15:9200
      index: aia-kb
    redis:
      host: 192.168.5.15
      port: 6379
      password: xxx
      key: aia-kb
    rerank:
      enabled: true                # 留空即沿用 embedding 的供应商和 key
    chunk-size: 500
    chunk-overlap: 50
    top-k: 5
```

启用后自动注册 `search_knowledge` 工具，并暴露接口：

```bash
# 写入/更新文档（docId 相同则覆盖，分片 id 为 docId#0、docId#1 ...）
POST /api/kb/documents   {"docId":"refund","text":"..."}   → {"docId":"refund","chunkCount":1}
# 按 docId 删除整篇文档
DELETE /api/kb/documents/{docId}
# 直接检索（调试用）
POST /api/kb/search      {"query":"退款","topK":3}          → [{id,text,score,metadata}]
```

## 5. 弹性降级

```java
class CustomerServicePolicy extends AbstractResiliencePolicy {
    protected Duration timeout() { return Duration.ofSeconds(10); }
    protected int maxRetries() { return 1; }
    protected int circuitBreakerThreshold() { return 3; }
    protected <T> T fallback(ResilienceContext ctx, Throwable cause) {
        return (T) "抱歉，当前咨询繁忙，请稍后再试";
    }
}
```

## 6. 配置存 MySQL

```java
// 注入 DataSource 后：
ConfigRepository repo = new JdbcConfigRepository(dataSource);
repo.saveAgent(new AgentConfig("a1", "系统提示词", "deepseek-chat",
    List.of("query_order"), List.of("logistics"), 0.5, 100));
repo.saveSkill(new SkillConfig("logistics", "物流", "指令", List.of("query_order")));
```

`AgentRuntime` 会从 `ConfigRepository` 加载 agent/skill 并构建 `AgentLoop`。

服务里切到数据库只需改配置（表在启动时自动 `CREATE TABLE IF NOT EXISTS`，无需手工建表）：

```yaml
aia:
  config:
    mode: jdbc                     # 默认 memory（重启即丢）
    datasource:
      url: jdbc:mysql://192.168.5.15:3306/ai_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
      username: your_user
      password: your_password
      driver-class-name: com.mysql.cj.jdbc.Driver
      maximum-pool-size: 5
```

需要用别的库（H2 / PostgreSQL）时只换驱动类与 url。`DataSourceAutoConfiguration` 已被排除，
只有 `mode=jdbc` 时才会创建数据源，所以不配数据库框架照常启动。

## 7. 子 agent 编排

把另一个 agent 包装成工具，主 agent 在 tool-calling 循环里委派给它：

```java
AgentRuntime runtime = new AgentRuntime(repo, registry, factory);

registry.register(runtime.subAgentTool("billing", "处理账单、退款、发票相关问题"));
// 主 agent 现在有一个 delegate_to_billing(task) 工具可用
```

- 子 agent 用**自己的** system prompt、模型、技能和工具作用域跑一整轮，把最终回答作为工具结果返回。
- 每次委派都按当前配置重建子 agent，配置改了立即生效。
- 天然支持多级嵌套；委派深度由 `SubAgentTool` 的 ThreadLocal 计数兜底，超过上限（默认 3）返回错误，
  避免 A→B→A 互相委派把 token 烧光。
- 谁能委派谁，同样由第 1.5 节的工具作用域控制：把 `delegate_to_billing` 写进某个 agent 的 `toolNames` 即可。

## 8. 模型供应商与功能模型适配

所有模型都走 **OpenAI 兼容协议**，所以一个适配器就能覆盖各家平台，差别只在 baseUrl、模型名和维度。
内置预设见 `com.aia.llm.provider.Providers`：

| provider | baseUrl | 默认对话模型 | 默认向量模型（维度） | 默认重排模型 |
|---|---|---|---|---|
| `deepseek` | api.deepseek.com/v1 | deepseek-chat | 无 | 无 |
| `dashscope` | dashscope.aliyuncs.com/compatible-mode/v1 | qwen-plus | text-embedding-v3 (1024) | 无 |
| `siliconflow` | api.siliconflow.cn/v1 | Qwen/Qwen2.5-7B-Instruct | Qwen/Qwen3-Embedding-0.6B (1024) | BAAI/bge-reranker-v2-m3 |
| `openai` | api.openai.com/v1 | gpt-4o-mini | text-embedding-3-small (1536) | 无 |
| `ollama` | localhost:11434/v1 | qwen2.5 | nomic-embed-text (768) | 无 |

接入新供应商（比如自建网关、Jina、Cohere）：

```java
Providers.register(new ProviderPreset(
    "my-gateway", "https://llm.my.com/v1", "gpt-4o", "bge-m3", 1024, "bge-reranker-v2-m3"));
```

之后 `aia.llm.provider: my-gateway` / `aia.rag.embedding.provider: my-gateway` 就能直接选它。
不想用预设时，任何一项都可以显式覆盖（`base-url` / `model` / `dimension` 填了就以填的为准）。

### 8.1 重排（Rerank）

检索准确率不够时开重排：先按 `topK × 4` 粗召回，再用重排模型收敛到 `topK`。

```yaml
aia:
  rag:
    rerank:
      enabled: true
      provider: ""     # 留空则沿用 embedding 的供应商、base-url 和 api-key
      base-url: ""
      api-key: ""
      model: ""        # 留空则用供应商预设，如 BAAI/bge-reranker-v2-m3
```

代码里等价于给 `KnowledgeBase` 多传一个 `RerankModel`：

```java
RerankModel rerank = new OpenAiCompatibleRerankModel(
    "https://api.siliconflow.cn/v1", "sk-xxx", "BAAI/bge-reranker-v2-m3", HttpClient.newHttpClient());
KnowledgeBase kb = new KnowledgeBase(embedder, store, new TextChunker(), 500, 50, rerank);
```

开了重排后，`SearchHit.score()` 返回的是重排模型的分数，不再是余弦相似度。

## 9. 多轮会话、答案缓存与上下文压缩

### 9.1 会话与记忆

```java
SessionStore store = new InMemorySessionStore();          // 或 Redis / JDBC / Composite
SessionSupport support = new SessionSupport(store, null, null, null);
AgentRuntime runtime = new AgentRuntime(repo, registry, factory, new DefaultToolResolver(), support);

runtime.chat("legal-intake", "u1", "公司把我辞退了");      // 第一轮
runtime.chat("legal-intake", "u1", "没有书面通知");        // 第二轮自动带上历史
```

存储选择：

| 实现 | 说明 |
|---|---|
| `InMemorySessionStore` | 进程内，重启即丢 |
| `RedisSessionStore` | HASH + TTL，热数据 |
| `JdbcSessionStore` | 会话主表 + 消息明细表，可审计 |
| `CompositeSessionStore` | Redis 热 + 数据库归档双写，读 miss 自动回源 |

### 9.2 上下文压缩

超过 `threshold-tokens` 时触发，三种策略：

| 策略 | 行为 |
|---|---|
| `summarize` | 调模型把早期对话压成摘要，保留最近 N 条原文 |
| `model-select` | 模型同时输出摘要 + 必须逐字保留的消息下标 |
| `truncate` | 直接丢弃早期消息（零成本兜底） |

三者都可以用 `FallbackContextCompressor` 套起来，主策略抛异常自动退回兜底。

### 9.3 热点问题缓存

```java
AnswerCache cache = new InMemoryAnswerCache(2000);   // 或 Redis / JDBC / Composite
```

同一个 agent 下「归一化后相同」的问题（去标点、统一大小写）直接返回缓存答案，
`ChatReply.fromCache()` 会标记命中。流式接口命中缓存时整段推送，客户端无感。

只有会话的槽位收集已完成时才会写入/读取缓存，避免把「半截案情」的回答复用出去。

### 9.4 检索路由

```java
RetrievalRouter router = new RuleBasedRetrievalRouter("您好，请问有什么可以帮您？", List.of("谢谢", "再见"));
```

- 空输入 / 短寒暄 → `CANNED`（固定话术，不调模型）或 `DIRECT`（调模型但不挂知识库工具）
- 实质问题 → `RETRIEVE`，正常走知识库

### 9.5 配置

```yaml
aia:
  session:
    enabled: true
    store: composite          # memory / redis / jdbc / composite
    ttl-seconds: 604800
    redis: { host: 192.168.5.15, password: xxx }
  answer-cache:
    enabled: true
    store: composite
    capacity: 2000
    ttl-seconds: 3600
  router:
    chitchat-mode: canned     # canned / model
    chitchat-reply: 您好，请问有什么可以帮您？
    extra-chitchat: 谢谢,再见
  compression:
    strategy: summarize       # summarize / model-select / truncate
    threshold-tokens: 3000
    keep-recent-messages: 8
    model: deepseek-chat
```

## 10. 槽位收集（信息采集编排）

`AgentConfig.slots` 声明要收集哪些信息，模型通过 `record_case_info` 工具回写：

```java
new AgentConfig("legal-intake", "你是接待顾问…", "deepseek-chat",
    List.of("*"), List.of(), 0.3, 800,
    List.of(new Slot("incident", "具体是什么事？", true),
            new Slot("when", "事情发生在什么时候？", true),
            new Slot("location", "工作地在哪个城市？", true),
            new Slot("detail", "能简单说说经过吗？", false)));
```

- 系统提示词里会自动带上「已收集 / 还差什么」，并给出提问话术
- 工具返回「已记录 X；待收集: …」，模型据此决定继续追问还是给结论
- 槽位随会话存在，多轮之间自动累积

配置来源两种：写进 agent 配置存 MySQL（`agent_config.slots`），或直接在代码/配置类里构造 `AgentConfig`。

## 11. 法律咨询示例

开启演示数据：

```bash
java -jar server/target/aia-server-0.1.0-SNAPSHOT.jar --aia.demo.enabled=true
```

会创建两个 agent 和一份劳动法知识库：

| agent | 职责 |
|---|---|
| `legal-intake` | 接待，按槽位收集「什么事 / 什么时候 / 在哪 / 经过」，收齐后委派给律师 |
| `legal-advisor` | 检索知识库后做法律分析，输出「法律问题 / 依据 / 可主张权益 / 下一步」 |

```bash
curl -X POST localhost:8080/api/chat/legal-intake \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"u1","message":"公司把我辞退了"}'
```
