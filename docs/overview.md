# 项目详情

面向想评估、二次开发或参与贡献的读者。接入怎么用请看 [`integration-guide.md`](integration-guide.md)，
部署怎么上请看 [`deployment.md`](deployment.md)。

---

## 1. 它解决什么问题

做 AI 客服或企业内部 Agent 时，每开一个新项目都要重写一遍：
调用大模型的 HTTP 封装、function calling 的循环、知识库的分片与检索、多轮会话、降级兜底……
这些事情跟业务无关，但每次都得做一遍，而且每次都做得不一样。

这个项目把这些**沉淀成一个可复用的 Java 底座**：业务代码只写「有哪些工具、提示词怎么写、知识是什么」，
其余的交给框架。

关键判断：**它是运行时，不是被调用方。** 框架自己发起对大模型的请求、自己驱动 tool calling 循环，
而不是等着外部 AI（比如 Claude Code）来调。这样才能在任何 Java 服务里独立工作。

## 2. 三个核心设计决策

### 2.1 分层：核心库零 Spring

```
core-llm → core → { core-rag, tools-http } → { config-jdbc, session-store } → server
```

`core` 及其上游**不依赖 Spring**，也能不依赖 Redis/ES/MySQL——全部通过 SPI 注入。

**为什么**：先用「单体引 jar」起步，等业务长大了再迁到独立服务。如果核心库绑死 Spring 或具体中间件，
迁移时业务代码得全改。现在从单体迁到 `server` 模块，业务代码基本不动。

代价：SPI 多了，初次接入要理解的东西比单体框架多一些。这是刻意的取舍。

### 2.2 模型访问统一走 OpenAI 兼容协议

`ChatModel` / `EmbeddingModel` / `RerankModel` 三个 SPI，各有一个 OpenAI 兼容实现。
DeepSeek、通义、硅基流动、OpenAI、Ollama 都走同一份代码，差别只是 baseUrl、模型名、维度。

**为什么**：换供应商时业务代码零改动，也不用为每家维护一个 SDK。
`Providers` 里内置了常用供应商的默认值，配置里写供应商名就行。

**代价**：供应商如果提供 OpenAI 协议之外的独有能力（比如通义的原生 rerank），用不上。

### 2.3 编排是「确定性 + 可兜底」，不是全靠模型

多步流程（收集信息 → 分派给专业 agent）默认靠模型主动调用工具，
但实测中**模型会忘记调用**——信息收齐了还在追问，永远不给结论。

所以框架在关键节点做确定性接管：必填槽位齐了且本会话还没分派过，运行时直接交给专业 agent，
不依赖模型自觉。指标里的 `source=tool|auto` 就是统计这两种路径各占多少。

**为什么**：把可靠性建立在提示词上，等于把系统的正确性交给概率。

## 3. 一次问答的完整链路

```
用户消息
  → 检索路由（寒暄？→ 固定话术直接返回，0 次模型调用）
  → 答案缓存（命中 → 直接返回）
  → 并发闸门（饱和 → 503 + Retry-After，不排队拖死）
  → 上下文压缩（超阈值 → 摘要 / 语义保留 / 截断）
  → 场景分派（必填信息齐了 → 换成专业 agent 的循环）
  → Agent 循环（模型 ↔ 工具，最多 N 轮）
      └ 检索工具 → 检索缓存(L1 内存/L2 Redis) → 向量化 → 向量库 → 重排
  → 记录会话 / 写答案缓存 / 打指标
```

每一环都可以关掉或替换。比如不需要会话就传 `SessionSupport.disabled()`，
不需要缓存就让 `AnswerCache` 为 null。

## 4. 扩展点一览

| 想改什么 | 实现哪个接口 | 内置实现 |
|---|---|---|
| 换模型供应商 | `ChatModel` / `EmbeddingModel` / `RerankModel` | OpenAI 兼容适配器 + 供应商预设 |
| 换向量库 | `VectorStore` | ES、Redis Vector Set、内存 |
| 换会话存储 | `SessionStore` | 内存、Redis、JDBC、两级 |
| 换答案/检索缓存 | `AnswerCache` / `RetrievalCache` | 内存、Redis、JDBC、两级 |
| 改哪些 agent 能用哪些工具 | `ToolResolver` | 白名单 + 通配符 |
| 改场景怎么分派 | `ScenarioRouter` | 关键词加权 / 向量 / LLM / 级联 |
| 改上下文怎么压 | `ContextCompressor` | 摘要 / 模型语义保留 / 截断 / 兜底组合 |
| 改什么算寒暄 | `RetrievalRouter` | 规则 + 可配置话术 |
| 改降级策略 | `AbstractResiliencePolicy` | 超时/重试/熔断/降级 |
| 改并发保护 | `Guard` | 限流、并发闸门 |
| 接监控系统 | `MetricsRecorder` | Micrometer（Prometheus） |
| 换配置存储 | `ConfigRepository` | 内存、JDBC |

## 5. 不适用 / 已知限制

诚实列出来，避免误用：

- **不是 RPA / 浏览器自动化**：没有视觉、没有 UI 操作能力。
- **不是多租户 SaaS**：没有用户体系、权限、计费。多团队共用一个实例需要自己加这一层。
- **流式请求统计不到 token**：流式响应没有 usage 字段，只能统计调用次数。
- **子 agent 的 token 记在主 agent 名下**：`AgentLoop` 只知道自己的模型名。
- **语义答案缓存不推荐**：实测精度只有 26%~86%，法律这类强个性化场景会答非所问（见 usage.md 第 16 节）。
- **场景分派是规则驱动的**：关键词/向量/LLM 三选一或级联，最复杂的多意图交叉仍可能判错（实测 3% 错误率）。

## 6. 质量保障

- **352 个自动化测试**，覆盖 8 个模块
- **评测集**：单轮 15 题、多轮 6 组、路由 32 题、缓存流量模拟，带回归门禁
- **真机验证**：ES、Redis、MySQL、DeepSeek、硅基流动、多实例、并发压测、优雅下线

评测不是摆设——它抓出过三个真问题：槽位提示词让模型无限追问、多轮评测只看末轮会误判、
多实例写入后其它实例缓存不失效。
