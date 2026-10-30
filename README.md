# AgentLoom · AI Agent 通用底座框架

一个可复用的 Java 模块，作为「AI 客服 / AI Agent」的通用底层。**自己就是 agent 运行时**，直接调用大模型 API，
内置 agent 管理、技能、RAG 知识库、多轮会话、场景分派、并发防护与可观测性。

## 文档导航

| 文档 | 什么时候看 |
|---|---|
| [项目详情](docs/overview.md) | 想评估这个项目、了解设计取舍与已知限制 |
| [接入指南](docs/integration-guide.md) | **想把它用到自己项目里，先看这个** |
| [使用案例](docs/use-cases.md) | 不确定适不适合你的场景，或想看别人怎么用 |
| [部署指南](docs/deployment.md) | 要上线：Docker、Nginx、多实例、监控、备份 |
| [详细用法](docs/usage.md) | 查具体 API 和配置项 |
| [设计文档](docs/superpowers/specs/2026-10-30-agentloom-design.md) | 想了解当初为什么这么设计 |

[github.com/Dongcs9512/java-agentLoom](https://github.com/Dongcs9512/java-agentLoom)

Java 17+ / Maven 多模块 / **核心库零 Spring 依赖** / [Apache-2.0](LICENSE)

## 30 秒理解

```java
ChatModel model = new OpenAiCompatibleChatModel("https://api.deepseek.com/v1", key, "deepseek-chat", client);
ToolRegistry tools = new ToolRegistry();
new ToolScanner().scan(new OrderTools()).forEach(tools::register);   // 注解定义工具

new AgentLoop(model, tools, "你是电商客服").run("订单 A123 到哪了");
// → 模型自己决定调用 query_order，拿到结果，再组织回答
```

不用写 if/else 判断意图，不用自己拼 function calling 的 JSON。

## 模块结构

| 模块 | 职责 |
|---|---|
| `core-llm` | `ChatModel` SPI、OpenAI 兼容适配器、供应商预设（deepseek/dashscope/siliconflow/openai/ollama） |
| `core` | Agent 循环、工具/技能 SPI 与四路定义、会话与压缩、检索路由、槽位收集、子/并行 agent、场景分派、并发防护、指标 SPI |
| `core-rag` | 分片、向量化、向量库（ES / Redis Vector Set / 内存）、重排、检索片段缓存（内存/Redis/两级 + 失效广播） |
| `tools-http` | 声明式 HTTP 工具 + 鉴权（静态 header / JWT / HMAC） |
| `config-jdbc` | agent/skill/槽位配置落库（MySQL / H2） |
| `session-store` | 会话与答案缓存的 Redis / JDBC 实现 |
| `server` | Spring Boot 服务壳：REST 接口、评测端点、Prometheus 指标、优雅下线 |
| `examples/minimal-agent` | 不依赖 Spring 的最小可运行示例 |

## 构建与运行

```bash
mvn test          # 全量测试
mvn install       # 装到本地仓库，供其它项目引用

java -jar server/target/agentloom-server-0.1.0-SNAPSHOT.jar \
  --agentloom.llm.provider=deepseek --agentloom.llm.api-key=sk-xxx \
  --agentloom.rag.enabled=true --agentloom.rag.embedding.provider=siliconflow --agentloom.rag.embedding.api-key=sk-yyy
```

跑示例（不依赖 Spring）：

```bash
export AGENTLOOM_API_KEY=sk-xxx
mvn -pl examples/minimal-agent dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "examples/minimal-agent/target/classes;$(cat examples/minimal-agent/cp.txt)" io.github.dongcs9512.agentloom.example.AgentDemo
```

## REST API

| 接口 | 说明 |
|---|---|
| `GET/POST/DELETE /api/agents` | agent 配置管理（含槽位定义） |
| `GET/POST/DELETE /api/skills` | skill 配置管理 |
| `POST /api/chat/{agentId}` | 问答，body `{message, sessionId}` → `{answer, sessionId, fromCache}` |
| `POST /api/chat/{agentId}/stream` | SSE 流式 |
| `POST /api/kb/documents`、`/api/kb/search` | 知识写入与调试检索 |
| `POST /api/eval/run`、`/dialogue`、`/routing`、`/cache` | 评测与回归门禁 |
| `GET /api/health` | 健康检查（关闭时切 draining） |
| `GET /actuator/prometheus` | 指标 |

## 能力一览

| 分类 | 能力 |
|---|---|
| 接入 | 多供应商 OpenAI 兼容；`Providers.register` 注册自定义；声明式 HTTP 工具对接微服务 |
| 对话 | 多轮会话 · 上下文压缩（摘要/语义保留/截断）· 检索路由 · 槽位收集 · 子 agent 串行/并行委派 · SSE 流式 |
| 知识 | 分片 · 向量化 · ES / Redis Vector Set / 内存三种向量库 · 元数据过滤 · 重排 · 检索缓存 |
| 编排 | 工作流（collect/dispatch/call/condition，配置化）· 场景分派（关键词 / 向量 / LLM / 级联） |
| 治理 | 工具作用域 · 并发闸门 · 单飞去重 · 限流 · 超时/熔断/降级 · 优雅下线 |
| 观测 | token 与调用次数、缓存分层命中、分段耗时、分派来源、Prometheus |
| 评测 | 单轮 15 题 · 多轮 6 组 · 路由 32 题 · 缓存收益 · 回归门禁 |

## 实测数据

| 项目 | 结果 |
|---|---|
| 单轮评测（劳动法 15 题） | 检索命中 100%，关键词覆盖 100% |
| 多轮评测（6 组场景） | 通过率 100%，分派全部正确 |
| 路由策略（32 题） | 级联 96.9% / 281ms；纯 LLM 96.9% / 771ms |
| 检索缓存 | 首次 136ms → 命中 2ms |
| 多实例 | 跨实例命中 31ms（对照 95ms），写入 1.2s 内全实例失效 |
| 并发闸门 | 上限 4 打 16 并发：4 成功 / 12 个 503，过载响应 213ms |
