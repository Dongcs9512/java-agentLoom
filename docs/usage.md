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

## 7. 子 agent 编排（预留）

`AgentLoop` 是单 agent 循环。子 agent 委派 / 并行编排作为后续扩展点：可新增 `DelegationPolicy` 接口，让 `AgentLoop` 在需要时把任务交给子 `AgentLoop`。当前版本未实现。
