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
    "http://localhost:9200",   // ES 地址
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
         -Dagentloom.es.url=http://localhost:9200 \
         -Dsurefire.failIfNoSpecifiedTests=false
```

### 4.3 Redis 实现（Redis 8+ 原生 Vector Set）

不需要 Redis Stack / RediSearch —— Redis 8.0 起内置了 `vectorset` 模块，直接用 `VADD` / `VSIM`。

```java
VectorStore vectorStore = new RedisVectorStore(
    "localhost", 6379, "password",
    "agentloom-kb",                     // Vector Set 的 key
    embedder.dimension());
```

- 向量以 FP32 小端二进制写入，正文与元数据存进属性（正文 key 为 `text`）。
- `search` 用 `VSIM ... WITHSCORES`，返回的是余弦相似度（1 = 完全一致）。
- 支持 `METADATA_FILTER`：`filter` 里的每个键值会翻译成 Vector Set 的 `FILTER '.docId == "refund"'`。
- 注意 `VREM` 一次只能删一个元素，`delete` 内部会逐个删除。
- 真实 Redis 冒烟测试（默认跳过）：

```bash
mvn test -Dtest=RedisVectorStoreLiveTest \
         -Dagentloom.redis.host=localhost -Dagentloom.redis.password=xxx \
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
agentloom:
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
      url: http://localhost:9200
      index: agentloom-kb
    redis:
      host: localhost
      port: 6379
      password: xxx
      key: agentloom-kb
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
agentloom:
  config:
    mode: jdbc                     # 默认 memory（重启即丢）
    datasource:
      url: jdbc:mysql://localhost:3306/ai_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
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
内置预设见 `io.github.dongcs9512.agentloom.llm.provider.Providers`：

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

之后 `agentloom.llm.provider: my-gateway` / `agentloom.rag.embedding.provider: my-gateway` 就能直接选它。
不想用预设时，任何一项都可以显式覆盖（`base-url` / `model` / `dimension` 填了就以填的为准）。

### 8.1 重排（Rerank）

检索准确率不够时开重排：先按 `topK × 4` 粗召回，再用重排模型收敛到 `topK`。

```yaml
agentloom:
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
agentloom:
  session:
    enabled: true
    store: composite          # memory / redis / jdbc / composite
    ttl-seconds: 604800
    redis: { host: localhost, password: xxx }
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
java -jar server/target/agentloom-server-0.1.0-SNAPSHOT.jar --agentloom.demo.enabled=true
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

## 12. 评测集（衡量答得准不准）

`evals/*.json` 是固定评测集，一套包含「知识文档 + 问题 + 期望命中的文档 + 期望出现的关键事实」：

```json
{
  "name": "劳动法咨询评测集",
  "documents": [{ "docId": "double-wage", "text": "劳动合同法第八十二条：…" }],
  "cases": [
    {
      "id": "no-written-contract",
      "question": "入职一年了公司一直没跟我签劳动合同，能要双倍工资吗？",
      "expectedDocIds": ["double-wage"],
      "expectedKeywords": ["二倍", "十一个月"]
    }
  ]
}
```

跑一次（`agentloom.eval.enabled=true`，`agentloom.eval.dataset-dir` 默认 `evals`）：

```bash
curl -X POST localhost:8080/api/eval/run -H 'Content-Type: application/json' \
  -d '{"dataset":"labor-law","agentId":"legal-advisor","topK":4,"ingest":true}'
```

三个指标：

| 指标 | 含义 |
|---|---|
| 检索命中率 | 期望文档是否出现在 topK 检索结果里 —— 衡量「找得到」 |
| 关键词覆盖率 | 回答里出现了多少比例的关键事实 —— 衡量「答得实」 |
| 通过率 | 检索命中 **且** 覆盖率 ≥ 阈值（默认 0.6） |

评测用的是**无会话、无缓存**的独立运行时，避免上一题影响下一题。

### 12.1 为什么要做文本归一化

模型会说「支付 200% 工资」，而关键字写的是「百分之二百」；说「11 个月」，关键字是「十一个月」；
说「30 天」，关键字是「30 日」。直接字符串匹配会把正确答案判成错的。

`AnswerNormalizer` 对**回答和关键字做同一套归一化**后再比对：中文数字转阿拉伯数字、
去掉「百分之」和 `%`、`日` 统一成 `天`、去空白标点。实测这一项把通过率从 80% 修正到 100%，
说明当时那 3 道「失败」全是格式误判，不是答案问题。

### 12.2 当前基线

`evals/labor-law.json`（15 题）在 DeepSeek + 硅基流动 Qwen3-Embedding + ES + bge-reranker 下的结果：

```
检索命中率 100%（15/15）· 平均关键词覆盖 100% · 通过率 100%（阈值 60%）
```

改动模型、切分参数、换向量库或改提示词后重跑这一条，就能立刻看出有没有退化。

### 12.3 回归门禁

`/api/eval/run` 的返回里带 `gate` 字段，任一项指标低于阈值即判定为退化：

```yaml
agentloom:
  eval:
    thresholds:
      retrieval-hit-rate: 0.95
      keyword-coverage: 0.85
      pass-rate: 0.90
```

```json
{ "gate": { "passed": false, "violations": ["检索命中率 80% 低于阈值 95%"] } }
```

`violations` 会列出**所有**掉线项，不是只报第一个——一眼能看出是哪一环退化了。
改模型、调 chunk 参数、换向量库后跑一次，`passed=false` 就说明这次改动把效果做坏了。

### 12.4 多轮对话评测

单轮评测测不到会话、槽位和委派，所以数据集里还有 `dialogues`：

```json
"dialogues": [{
  "id": "labor-dismissal-full",
  "agentId": "legal-intake",
  "requiredSlots": ["incident", "when", "location"],
  "finalExpectedKeywords": ["违法解除", "仲裁", "2N"],
  "turns": [
    { "user": "公司把我辞退了", "expectKeywords": ["事"] },
    { "user": "上周五口头让我走的，没有书面通知", "expectKeywords": ["城市"] },
    ...
  ]
}]
```

```bash
curl -X POST localhost:8080/api/eval/dialogue -H 'Content-Type: application/json' \
  -d '{"dataset":"labor-law","ingest":false}'
```

判定：**必填槽位全部收集** 且 **整段对话的回答覆盖 ≥60% 的关键事实**。每组对话用独立的内存会话，互不干扰。

两个实测中踩过的坑，值得记住：

1. **不能只看最后一轮的回答**。分析往往在第 3 轮就给出了，第 4 轮只是追问补充信息；只看末轮会把答对的判成没答。
   现在按整段对话拼接后统一算覆盖。
2. **关键词要选措辞稳定的**。「赔偿」不如「违法解除 / 2N / 仲裁」稳——模型常用具体术语而不说笼统词。
   留 3 个关键词、阈值 60%，允许漏一个，避免单次措辞差异导致误判。

### 12.5 当前基线

| 评测 | 结果 |
|---|---|
| 单轮（15 题） | 检索命中 100%，关键词覆盖 100%，门禁 passed=true |
| 多轮（2 组对话） | 通过率 100%，平均槽位完成度 100% |

> 多轮评测样本还少（2 组），单次通过率受模型措辞波动影响。建议后续补到 6~8 组再当硬门禁用。

## 13. 场景路由（分派给对应的专业 agent）

接待 agent 收集完信息后，按内容**确定性**地交给对应专业 agent，而不是让模型自己猜找谁。

```yaml
agentloom:
  scenarios:
    enabled: true
    default-agent: legal-labor
    rules:
      # 关键词后加 *N 表示权重
      - agent: legal-welfare
        keywords: 社保*3,社会保险*3,公积金*3,工伤*3,产假*2,孕期*2,哺乳期*2,年假*2
      - agent: legal-contract
        keywords: 竞业*3,保密*2,违约金*2,试用期*2,无固定期限*2
      - agent: legal-labor
        keywords: 辞退,解雇,开除,欠薪*2,拖欠工资*2,加班费*2,经济补偿,赔偿金,违法解除*2
```

匹配规则：命中关键词的**权重求和**，得分最高者胜出；同分按配置顺序；都没命中走 `default-agent`。

### 13.1 两条保障路径

1. **模型主动调用**：给接待 agent 自动挂上 `delegate_to_specialist` 工具，模型可以主动转派。
2. **框架兜底（关键）**：必填槽位齐了、且本会话还没分派过，运行时**直接**把这一轮交给专业 agent，
   不再依赖模型"记得调用工具"。实测中模型确实会忘记——收集齐信息后继续追问，永远不给结论。

分派结果会写进会话的 `__scenario` 槽位，便于链路追踪与评测断言。

转派给专业 agent 时，接待阶段收集的案情会自动拼进系统提示词，专业 agent 不需要重问一遍。

### 13.2 为什么不只用向量或 LLM 分类

关键词 + 权重命中足够快、零成本、可解释，出问题能直接看出是哪条规则判错了。
真需要语义判断时，实现 `ScenarioRouter` 接口替换即可，接口只有一个方法。

## 14. 当前基线（完整）

| 评测 | 结果 |
|---|---|
| 单轮 15 题 | 检索命中 100%，关键词覆盖 100%，**gate passed** |
| 多轮 6 组 | 通过率 100%，槽位完成度 100% |

6 组多轮覆盖：违法辞退、欠薪、加班费（→ legal-labor）、竞业限制（→ legal-contract）、
自愿放弃社保、工伤（→ legal-welfare）。每题都断言了**分派到的 agent 是否正确**。

## 15. 路由策略对比与选型（实测数据）

同一批 **32 道标注问题**（含 4 道应判为"无法归类"、5 道多意图交叉），四种策略实测：

| 策略 | 准确率 | 平均延迟 | p95 延迟 | 说明 |
|---|---|---|---|---|
| **cascade（推荐）** | **96.9%** | **281ms** | 1097ms | 关键词快通道 → 向量 → LLM 逐级兜底 |
| llm | 96.9% | 771ms | 1066ms | 每单必调一次模型 |
| embedding | 81.2% | 414ms | 2321ms | 每单一次向量化 |
| keyword | 62.5% | 0ms | 0ms | 零成本，但改写说法就漏 |

裸关键词只有 62.5%：用户说「不给我发工资」而不是「拖欠工资」就匹配不上。
裸向量 81.2%：语义能救回一部分改写，但「保密协议」「生育津贴」仍会掉到阈值以下。
**级联**把两者优点叠起来——90% 的请求在 0ms 关键词这一层就结束了，剩下的才付费，
所以准确率追平纯 LLM 的同时平均延迟只有它的 1/3。

### 15.1 级联的三级门槛

```
一级 keyword（得分 ≥ min-score=3）   → 直接采纳，0 成本
二级 embedding（相似度 ≥ 0.62）      → 采纳
三级 llm（分类）                      → 兜底，也负责判"无法归类"
```

`min-score: 3` 很关键：设成 2 时快通道会抢答错（「试用期最后一天被开除了」被 contract 的
「试用期*2」截胡），实测从 93.8% 提到 96.9%。

### 15.2 怎么选

```yaml
agentloom:
  scenarios:
    strategy: cascade     # keyword / embedding / llm / cascade
    min-score: 3
```

- **cascade**：默认，个人/中小企业场景够用
- **llm**：法规频繁变动、场景边界模糊、要求可解释性高的场景（每单多花约 0.5 秒）
- **keyword**：纯内网、无外网模型调用、对方是固定话术入口的场景
- **embedding**：只想省模型调用又不想维护关键词时，但要接受 80% 左右的准确率

实测命令：

```bash
curl -X POST localhost:8080/api/eval/routing -H 'Content-Type: application/json' \
  -d '{"dataset":"routing","llmModel":"deepseek-chat"}'
```

返回每种策略的准确率、平均/p95 延迟和**具体错在哪几题**，改规则或换模型后重跑即可对比。

### 15.3 已知短板

级联当前剩 1 题错误（3%）：「离职后不能去同行公司上班」被向量判成 labor。
根因是关键词没命中、向量给了次优答案，而级联在**向量有答案时就不会再问 LLM**。

要压掉最后这几个点，可以让向量只在高置信（如 ≥0.72）时才采纳，否则继续下探到 LLM。
代价是这类请求会慢到 700ms 级别。是否值得，取决于业务对错误分派的容忍度。

### 15.4 首字延迟实测（LLM 路由 vs 级联）

**关键点：分派的那次 LLM 调用是阻塞前置的** —— 必须等分类结果出来才知道交给谁，
用户在这期间一个字都看不到。所以：

```
LLM 路由的首字时间 = 分类调用完成时间 + 回答首字时间
级联的首字时间     = 回答首字时间（关键词命中时完全跳过分类）
```

实测 DeepSeek（同一网络，流式）：

| 环节 | 并发 1 | 并发 20 |
|---|---|---|
| 分类调用（max_tokens=20）完成 | p50 887ms / p95 898ms | p50 348ms / p95 998ms |
| 回答首字（max_tokens=800） | p50 819ms / p95 929ms | p50 676ms / p95 1198ms |

**结论：用 LLM 路由，用户首字时间约 1.6~1.8 秒；级联约 0.8 秒。**

注意一个反直觉的点：分类只需要输出 20 个 token，但**首 token 延迟跟输出长度无关**，
它取决于模型调度与首 token 计算，实测同样在 700~900ms。所以"分类很短所以很快"是错觉。

高并发下还有两个隐性代价：

1. **模型调用量翻倍** —— 每一次用户提问会多消耗一次模型调用，QPS 和成本同步翻倍
2. **串行等待** —— 分类必须做完才能开始回答，两段延迟直接相加，无法重叠

所以在高请求量场景下推荐 `cascade`：关键词命中（实测占 90% 以上）时分类成本为零，
只有判不出来的请求才走 LLM，且这部分本来就是少数。

若确实要用纯 LLM 路由，可考虑：分类结果按归一化问题缓存（同一问题第二次直接命中）、
或换更小更快的模型专门做分类。

## 16. 答案缓存收益评估（高并发场景）

`evals/cache-traffic.json` 模拟真实流量：**8 个热门问题（每个 5 种问法）+ 30 条互不相同的个案描述**，
热门问题按「很多人问同一句」采样，个案按「每人说的都不一样」采样。

```bash
curl -X POST localhost:8080/api/eval/cache -H 'Content-Type: application/json' \
  -d '{"dataset":"cache-traffic","requests":300,"hotPercent":55}'
```

### 16.1 实测结果（300 次请求）

| 热门占比 | 精确缓存命中 | 语义缓存@0.85 命中 | 语义缓存精度 | 语义缓存误命中 |
|---|---|---|---|---|
| 30% | 19.3% | 79.0% | 26.2% | 175 次 |
| 55% | 45.7% | 78.3% | 60.4% | 93 次 |
| 80% | 65.7% | 78.3% | 86.0% | 33 次 |

**精确缓存精度始终 100%**（只复用一字不差的问法）。

**语义缓存是陷阱**：不管热门占比多少，它都能"命中"约 80%——因为同一领域的问题向量天然相近，
但它会把热门 FAQ 的答案套到完全不同的个案上。热门占比 30% 时，命中里只有 26% 是对的，
意味着一百多次用户会收到**答非所问的法律意见**。而且把阈值从 0.75 提到 0.95 几乎不起作用
（精度只从 29.3% 到 24.9%），因为差异不在相似度，而在「这些个案本来就该各自回答」。

真正的增量：热门 55% 时，语义缓存有效命中 47.3% vs 精确缓存 45.7%，**多出 1.6 个百分点，
代价是 93 次错误复用**。不划算。

### 16.2 结论与建议

1. **只用精确缓存，禁止对个案描述做语义复用。** 法律咨询的回答强依赖案情细节，复用即事故。
2. **缓存收益完全取决于热门占比**：客服入口如果集中展示常见问题，热门占比能到 60%+，
   那么近一半请求可以省掉模型调用；如果是纯自由文本咨询，命中率会很低。
3. **配合首字延迟**：缓存命中是毫秒级，完整链路约 1.5~2s。热门 55% 时，
   平均首字从约 1500ms 降到约 800ms，模型调用量减半。
4. **当前实现的限制**：`AgentRuntime` 只在会话槽位收集完成后才读写缓存（避免复用半截案情的回答）。
   对 `legal-intake` 这种要收集案情的 agent，纯 FAQ 提问会被这条规则挡掉。
   如果确认不需要这个保护，可以放宽——但**对有槽位收集的 agent 建议保留**。

### 16.3 什么情况下语义缓存才安全

- 答案与个案无关（纯政策问答、办事流程）
- 或缓存的是**知识片段**而不是**最终回答**（相当于把我们已有的 RAG 检索缓存住，粒度更安全）

后者更值得做：缓存的是「这个问题命中了哪几篇法条」，即使相似度高一点也不会给出错误结论。

## 17. 高并发优化与防护

### 17.1 已修复的隐形杀手：每请求新建 HttpClient

原来 `chatModelFactory` 每次都 `HttpClient.newHttpClient()`，等于每次模型调用都新建连接池、
重新做 TCP/TLS 握手。高并发下既多几十毫秒，又制造大量 TIME_WAIT。

现在全局复用一个 `HttpClient`（`agentloom.http.connect-timeout-ms` 可配），聊天/向量化/重排/ES 共用。

### 17.2 检索结果缓存（推荐开启）

缓存的是「这个问题命中哪几篇片段」，不是回答——模型仍会针对每个用户的案情重新作答，
**粒度安全**。省掉的是向量化 + 向量检索 + 重排三次外部调用。

实测（`POST /api/kb/search` 同一问题连打 5 次）：

```
第 1 次  136ms   （向量化 + 检索）
第 2~5 次  2~4ms  （命中缓存，快 38 倍）
换一个问题  96ms  （未命中）
```

知识库写入（新增/更新/删除文档）会自动清空缓存，不会出现"新文档搜不到"。

```yaml
agentloom:
  rag:
    cache:
      enabled: true
      capacity: 2000
      ttl-seconds: 300    # 多实例部署时兜底，防止别的实例写入后这里看不到
```

### 17.3 并发去重（SingleFlight）

同一问题被同时问爆（活动开始、缓存刚过期）时，只让一个请求真正执行，其余等它的结果。

已验证：8 个线程同 key 并发 → 只执行 1 次；不同 key 各执行各的；主执行失败时等待方拿到同样的异常。

### 17.4 并发闸门（快速失败）

```yaml
agentloom:
  concurrency:
    max-concurrent: 32        # 同时最多处理多少个请求
    max-wait-ms: 2000         # 排队最多等多久，等不到就 503
    rate-limit-per-second: 0  # >0 时启用每秒限流
```

实测（上限 4、排队 200ms，打 16 个并发）：

```
成功 200：4 个      过载 503：12 个
过载响应 p50 = 213ms（快速失败，而不是拖 8 秒超时）
```

过载统一返回 **503 + `Retry-After: 1`**，调用方知道"稍后重试"而不是当成服务故障。

### 17.5 顺带修掉的一个泄漏

`GuardChain` 原来在一串 guard 里，如果第 2 个 `before` 抛异常，第 1 个已获取的信号量
不会被释放。现在只对**成功进入**的 guard 执行 `after`，并按反序释放。

### 17.6 其他可选的优化（按性价比排序）

| 方案 | 收益 | 代价 | 建议 |
|---|---|---|---|
| 检索结果缓存 | 命中时省 3 次外部调用 | 写入需失效 | **已实现，默认开** |
| 并发闸门 + 快速失败 | 雪崩防护 | 少量请求被拒 | **已实现，默认开** |
| 共享 HttpClient | 省握手、省端口 | 无 | **已实现** |
| 答案缓存（精确） | 命中省整条链路 | 案情类命中率低 | 已有，按需开 |
| 分类用更小的模型 | 分类从 ~800ms 降到 ~200ms | 准确率略降 | 可选 |
| 供应商侧 prompt 缓存 | 省输入 token 成本 | 依赖供应商支持 | 看账单决定 |
| 语义答案缓存 | 命中率虚高、精度差 | 会答非所问 | **不建议**（见 16 节） |
| 流式 + 工具调用合并 | 首字更快 | 实现复杂 | 后续可做 |

### 17.7 一句话总结

高并发下的第一优先级不是"更快"，而是**"不雪崩"**：
限住并发、快速失败、把重复的外部调用砍掉。这三件事做完，单机撑住几十并发没有问题；
再往上就该考虑多实例 + Redis 共享缓存了。

### 17.8 缓存该放哪：内存 / Redis / 两级

多实例部署时进程内缓存各存一份，命中率会打折——**需要 Redis 共享**。
但把 Redis 当唯一缓存，每次命中都要多一跳网络。实测对比（局域网 Redis）：

| 场景 | 耗时 | 说明 |
|---|---|---|
| 内存命中（L1） | 2.2ms | 无网络 |
| Redis 命中（L2） | 3.3ms | 含一次往返 + JSON 解析 |
| 两级：L1 未命中走 L2 | 14ms | 回填 L1 后转为 2ms |
| 未命中（向量化+检索） | 80~130ms | 真正的外部调用 |

差距只有 1ms 左右，所以**不是"必须用 Redis 才够快"**，而是：

- **单实例 / 少量实例**：`memory` 就够了，最快且零依赖
- **多实例**：用 `composite`（L1 本地 + L2 Redis）。热门问题每台机器只多走一次网络，
  之后都是本地命中，同时享受跨实例命中率
- **只求共享、不在乎那 1ms**：`redis`

```yaml
agentloom:
  rag:
    cache:
      store: composite        # memory / redis / composite
      capacity: 2000
      ttl-seconds: 300
      redis:
        host: localhost
        password: xxx
        key-prefix: "agentloom:retrieval:"
```

实测跨进程：重启后 L1 为空，第一个问题直接从 Redis 回源（14ms），
第二个请求就回到 2ms 的本地速度——**这就是多实例共享的效果**。

### 17.9 一个容易踩的坑：写入会清空共享缓存

知识库只要发生写入（新增/更新/删除文档），`RetrievalCache.clear()` 就会清空——
多实例部署时，一台机器写入会清掉所有人的缓存。这是**正确**的（否则新文档搜不到），
但要避免频繁触发：

- 生产上**务必用持久化向量库**（`agentloom.rag.store=es`）。
  用 `memory` 的话每次重启都要重新灌知识库，等于每次重启都清空一次共享缓存
- 批量导入文档时集中在低峰期，或导入后短暂预热

其余两类缓存（答案缓存、会话）本来就是 `memory / redis / jdbc / composite` 可配的，
高并发多实例场景同样建议切到 `composite` 或 `redis`。

### 17.10 多实例实测

两个进程（8081 / 8082）共用同一 Redis 检索缓存，各自独立进程内缓存：

```
=== 跨实例缓存共享 ===
A 首次查询（未命中）        114ms
A 再次查询（L1 命中）         2ms
B 首次查询同一问题（L1 空）  31ms   ← 走 Redis 共享缓存
B 再次查询（L1 已回填）       2ms
B 查询全新问题（对照）        95ms
```

**B 从没查过这个问题，31ms 拿到结果**（对照 95ms）——共享生效。

```
=== 并发分流 ===
20 个并发分散到两实例：全部成功（A=10 / B=10），总耗时 14ms，p50 5ms
```

### 17.11 多实例踩到的真 bug：写入后跨实例不失效

第一版实测发现：A 写入新文档后，**B 仍然 2ms 返回旧结果**。

原因是 `clear()` 只清了执行写入那个进程的本地缓存；B 进程的 L1 还在，TTL 内都是旧数据。
这在生产上意味着：**更新知识库后，其它实例可能继续用旧内容回答**——法律咨询场景下是事故。

修复方案是**版本号 + 定期比对**：

- 共享缓存（Redis）维护一个 `agentloom:retrieval:__ver`，每次 `clear()` 递增
- 每个实例每 `agentloom.rag.cache.version-check-ms`（默认 2000ms）比对一次版本号
- 发现变化就清掉自己的 L1

修复后同一测试：A 写入 → 1.2 秒后 B 再查 = **96ms**（重新检索），不再是 2ms 的旧数据。

代价是**最多 N 毫秒的陈旧窗口**（默认 2 秒），换来每次请求零额外开销——
版本号只在检查周期到了才读一次 Redis，不是每个请求都读。
对"知识库更新后几秒内全量生效"的业务来说完全够用；要更实时可以上 Redis Pub/Sub 推送失效。

### 17.12 实时失效：Redis Pub/Sub

版本号轮询有最多 2 秒的陈旧窗口。要更实时可以开广播：

```yaml
agentloom:
  rag:
    cache:
      pubsub-enabled: true    # 默认开
      version-check-ms: 2000  # 轮询保留作为兜底
```

写入知识库时，除了清空共享缓存，还会向 `agentloom:retrieval:__invalidate` 频道发一条消息；
每个实例都有后台订阅线程，收到就立刻清掉自己的本地缓存。**陈旧窗口从秒级降到毫秒级。**

- 订阅使用独立连接和线程，断线自动重连
- 轮询兜底保留：万一广播期间订阅断过，最多 2 秒后仍会通过版本号纠正
- 已验证：实例 A 清缓存后约 0.4 秒内，实例 B 的本地缓存即失效

## 18. 优雅下线

滚动发布时最怕的是：进程被杀，正在生成的回答被切断，用户看到半截。

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

配合健康检查：

```bash
curl localhost:8080/api/health
# {"status":"up","uptimeMs":123456}
```

收到关闭信号后状态立刻变成 `draining`，网关/LB 据此摘流量；在途请求继续跑完。

**验证方式**：发起一个 3 秒的慢请求，0.8 秒后关闭 Spring 上下文——
请求仍然返回 200 且完整耗时 3 秒。把配置换成 `shutdown: immediate` 再跑，
请求立刻报 `HTTP/1.1 header parser received no bytes`（连接被掐断），
证明这个测试确实能区分两种行为。

### 18.1 上线清单

- [ ] `agentloom.rag.store=es`（多实例必须共用持久化向量库，否则各实例知识不一致）
- [ ] 会话/答案缓存/检索缓存切 `composite` 或 `redis`
- [ ] `agentloom.concurrency.max-concurrent` 按单机承载能力设置
- [ ] 网关接入 `/api/health`，摘流量后再关进程
- [ ] 关闭信号用 SIGTERM（K8s 默认），不要用 kill -9

## 19. 可观测性（Prometheus 指标）

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
```

```bash
curl localhost:8080/actuator/prometheus
```

### 19.1 暴露的指标

| 指标 | 类型 | 说明 |
|---|---|---|
| `agentloom_agent_chat_seconds` | Timer | 一次问答耗时，标签 `agent` / `outcome`(ok/error) / `stream` |
| `agentloom_rag_search_seconds` | Timer | 检索耗时（向量化 + 检索 + 重排），标签 `rerank` |
| `agentloom_cache_retrieval_total` | Counter | 检索缓存命中/未命中，标签 `result`(hit/miss) |
| `agentloom_guard_rejected_total` | Counter | 因并发饱和/限流被拒的请求数 |

核心库不依赖 Micrometer：`MetricsRecorder` 是纯接口，默认空实现；
Spring 侧 `MicrometerMetricsRecorder` 把它接到 `MeterRegistry`。
以后换 Dropwizard / OTel 只需再写一个实现。

### 19.2 实测输出

```
agentloom_cache_retrieval_total{result="hit"}  2.0
agentloom_cache_retrieval_total{result="miss"} 6.0
agentloom_agent_chat_seconds_count{agent="legal-labor",outcome="ok"} 1
agentloom_guard_rejected_total 1.0
```

（并发上限设为 1，同时打两个聊天请求：一个 200、一个 503，过载计数如实 +1。）

### 19.3 值得配的告警

| 告警 | 条件 | 含义 |
|---|---|---|
| 缓存命中率过低 | `rate(agentloom_cache_retrieval_total{result="hit"}[5m]) / rate(agentloom_cache_retrieval_total[5m]) < 0.3` | 缓存没起作用或知识库在频繁写入 |
| 过载拒绝持续 | `rate(agentloom_guard_rejected_total[5m]) > 0` 持续 10 分钟 | 单机扛不住，该扩容或调 `max-concurrent` |
| 问答变慢 | `histogram_quantile(0.95, rate(agentloom_agent_chat_seconds_bucket[5m])) > 10` | 模型侧或检索侧退化 |
| 错误率升高 | `rate(agentloom_agent_chat_seconds_count{outcome="error"}[5m]) > 0` | 模型/网络异常 |

### 19.4 还缺什么

- **模型调用次数与 token 消耗**：需要在 `ChatModel` 层埋点（目前只在 agent 层测了整体耗时）
- **场景分派计数**：哪个专业方向被分派了多少次，便于发现规则偏差
- **链路追踪**：一次请求经过 分类 → 检索 → 生成 → 委派 的分段耗时，用 OpenTelemetry 更合适

这三项要不要做，取决于是否要接入完整的 APM。

### 19.5 成本与分派指标（第二轮补充）

| 指标 | 标签 | 用途 |
|---|---|---|
| `agentloom_llm_calls_total` | `model` / `stream` | 模型调用次数，直接对应计费次数 |
| `agentloom_llm_tokens_total` | `model` / `type`(prompt/completion) | token 消耗，按输入/输出分开 |
| `agentloom_cache_retrieval_level_total` | `level`(l1/l2) | 检索缓存命中在内存还是 Redis |
| `agentloom_cache_retrieval_total` | `result`(hit/miss) | 检索缓存整体命中率 |
| `agentloom_answer_cache_total` | `result`(hit/miss) | 答案缓存命中率 |
| `agentloom_scenario_dispatch_total` | `agent` / `source`(tool/auto) | 各专业方向被分派次数 |
| `agentloom_stage_decision_total` | `decision`(CANNED/DIRECT/RETRIEVE) | 检索路由决策分布 |
| `agentloom_stage_seconds` | `stage`(route/cache/compress) | 各阶段耗时 |
| `agentloom_guard_rejected_total` | — | 过载拒绝数 |

**`source` 标签很重要**：`tool` 表示模型主动调了分派工具，`auto` 表示框架在必填槽位齐了之后
确定性接管。这个比例能告诉你模型靠不靠得住——`auto` 占比高说明模型经常忘记调工具。

实测输出：

```
agentloom_llm_calls_total{model="deepseek-chat",stream="false"} 7.0
agentloom_llm_tokens_total{model="deepseek-chat",type="prompt"} 10775.0
agentloom_llm_tokens_total{model="deepseek-chat",type="completion"} 3839.0
agentloom_scenario_dispatch_total{agent="legal-labor",source="tool"} 1.0
agentloom_cache_retrieval_level_total{level="l1"} 1.0
agentloom_cache_retrieval_level_total{level="l2"} 1.0
agentloom_stage_seconds_count{stage="route"} 2
agentloom_stage_seconds_count{stage="cache"} 1
agentloom_stage_seconds_count{stage="compress"} 1
```

### 19.6 成本估算 PromQL

```promql
# 近 1 小时 token 总量
sum by (type) (increase(agentloom_llm_tokens_total[1h]))

# 按 DeepSeek 定价粗算成本（输入 1 元/百万 token、输出 2 元/百万 —— 以实际价格为准）
sum(increase(agentloom_llm_tokens_total{type="prompt"}[1h])) / 1e6 * 1
+ sum(increase(agentloom_llm_tokens_total{type="completion"}[1h])) / 1e6 * 2

# 单次问答平均 token
sum(rate(agentloom_llm_tokens_total[5m])) / sum(rate(agentloom_agent_chat_seconds_count[5m]))

# 关键词路由 / 缓存替用户省下的模型调用
sum(rate(agentloom_stage_decision_total{decision="CANNED"}[5m]))
+ sum(rate(agentloom_answer_cache_total{result="hit"}[5m]))
```

### 19.7 已知限制

- **流式请求统计不到 token**：流式响应里没有 usage 字段，只能统计调用次数（`stream=true`）。
  要精确统计得在流结束时补一次计费查询，或改用非流式。
- **子 agent 的 token 记在主 agent 名下**：`AgentLoop` 只知道自己的模型名，不区分调用来源。

## 20. 工作流编排

前面几节的能力是"零件"，工作流把它们按顺序串起来，并且**可以配置而不改代码**。

### 20.1 定义（当前走配置文件）

```yaml
agentloom:
  workflows:
    items:
      - id: legal-intake-flow
        entry-agent: legal-intake        # 接待 agent
        steps:
          - id: collect
            type: collect                # 收集信息
            slots: [incident, when, location]
            max-turns: 5                 # 追问上限，到了就带着已有信息往下走
          - id: dispatch
            type: dispatch               # 分派（不调模型，走场景路由）
          - id: answer
            type: call
            agent: "@routed"             # 交给分派结果
```

步骤类型只有四种：

| type | 作用 | 关键字段 |
|---|---|---|
| `collect` | 跑接待 agent，直到指定槽位齐了或到轮次上限 | `slots` / `max-turns` |
| `dispatch` | 按会话内容判断交给谁，不调模型 | — |
| `call` | 跑指定 agent，产出最终回答 | `agent`（或 `@routed`） |
| `condition` | 按槽位取值跳转 | `when` / `then` / `otherwise` |

条件语法刻意做得极简，不引入脚本引擎：

```yaml
- id: check
  type: condition
  when: slots.amount > 10000
  then: handoff        # 跳到步骤 id
  otherwise: auto
- id: handoff
  type: call
  agent: human-agent
- id: auto
  type: call
  agent: bot-agent
```

支持 `== != > < >= <= contains`，左边必须是 `slots.<字段名>`。

### 20.2 为什么状态要存在会话里

「收集」这一步需要用户回答，引擎没法自己跑完。所以工作流**跨用户消息推进**：

```
用户第 1 句 → COLLECT 没齐 → 返回追问，状态存进会话（__wf_step / __wf_turns）
用户第 2 句 → COLLECT 齐了 → 同一轮里继续 DISPATCH + CALL → 返回最终回答
```

会话里的 `__` 前缀槽位不会出现在给模型看的提示词里。

### 20.3 配置错误在启动时暴露

`toDefinitions()` 会校验：条件能解析、跳转目标存在、CALL 有 agent、COLLECT 有槽位。
写错直接启动失败，不会等到线上跑到一半才炸。

单轮推进步数也有上限（20 步），防止条件写成互相跳转的死循环。

### 20.4 存数据库（已支持）

工作流可以放在数据库里，**改流程不用重启、多实例自动共享**：

```yaml
agentloom:
  workflows:
    load-from-db: true        # 开启后数据库优先于配置文件
    db-cache-ttl-ms: 10000    # 缓存多久重新读一次库（避免每请求查库）
```

表结构在建库脚本 [`init.sql`](../init.sql) 里（`workflow_config`），
应用启动时也会自动建表。

**用接口管理，不用写 SQL：**

```bash
# 列出当前生效的工作流（source 标明来自 database 还是 config-file）
curl localhost:8080/api/workflows

# 新增/覆盖（保存即生效，其它实例最多 10 秒后同步）
curl -X POST localhost:8080/api/workflows -H 'Content-Type: application/json' -d '{
  "id": "refund-flow",
  "entryAgent": "refund-intake",
  "steps": [
    {"id": "collect", "type": "COLLECT", "slots": ["orderId", "reason"], "maxTurns": 3},
    {"id": "dispatch", "type": "DISPATCH"},
    {"id": "answer", "type": "CALL", "agent": "@routed"}
  ]
}'

# 删除
curl -X DELETE localhost:8080/api/workflows/refund-flow
```

保存前会校验：条件能解析、跳转目标存在、CALL 有 agent、COLLECT 有槽位。
**写错直接被拒，不会把坏配置写进库。**

优先级：数据库 > 配置文件。数据库里没有的入口 agent 才回落到 YAML，
所以你可以「配置文件放基线、数据库放线上临时调整」。

### 20.5 已知限制

- **流式**：工作流涉及多步编排，`/stream` 接口目前整段推送，不逐字。
  单 agent 路径（不挂工作流）仍然逐字流式。
- **流程结束后重新开始**：一条会话的流程走完会清空状态，下一条消息重新从第一步开始。
  如果你需要"结束后继续和同一个专业 agent 聊"，得再加一个"停留"步骤类型。
- **数据库变更的生效延迟**：其它实例最多 `db-cache-ttl-ms`（默认 10 秒）后才看到新流程。
  要立即生效可以调小 TTL，或手动调用一次 `WorkflowRegistry.refresh()`。
