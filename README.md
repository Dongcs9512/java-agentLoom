# AI Agent 通用底座框架

一个可复用的 Java 模块，作为「AI 客服 / AI Agent」的通用底层。自己就是 agent 运行时，直接调用大模型 API，内置 agent 管理、skill/技能、RAG 知识库、弹性降级、配置存储。

- 设计文档：`docs/superpowers/specs/2026-10-30-ai-agent-framework-design.md`
- 使用指南：`docs/usage.md`
- Java 17+，Maven 多模块，核心库零 Spring 依赖。

## 模块结构

| 模块 | 职责 |
|---|---|
| `core-llm` | `ChatModel` SPI + OpenAI 兼容适配器（一个覆盖 DeepSeek/千问/Ollama） |
| `core` | agent 引擎（`AgentLoop`）、`Tool`/`Skill` SPI、四路工具定义、`ConfigRepository` SPI、弹性 `ResiliencePolicy`、`Guard` 链 |
| `core-rag` | RAG 管道：`TextChunker` 分片、`EmbeddingModel`/`VectorStore` SPI、`KnowledgeBase`（含内存向量库） |
| `tools-http` | 声明式 HTTP 工具 `HttpTool` + `Authenticator` SPI（静态 header / JWT / HMAC 签名） |
| `config-jdbc` | `ConfigRepository` 的 JDBC 实现（MySQL / H2，纯 JDBC） |
| `server` | Spring Boot 服务壳：REST 配置管理 + 聊天接口 |

## 快速开始

```bash
# 构建 + 全量测试（本机 Maven）
E:\claudePoject\tools\apache-maven-3.9.16\bin\mvn test

# 启动服务（先配置 server/src/main/resources/application.yml 的 aia.llm.base-url/api-key）
cd server
E:\claudePoject\tools\apache-maven-3.9.16\bin\mvn spring-boot:run
```

## 配置大模型

`server/src/main/resources/application.yml`：

```yaml
aia:
  llm:
    base-url: "https://api.deepseek.com/v1"   # 或千问/ollama 的 OpenAI 兼容地址
    api-key: "sk-xxx"
```

## REST API

- `GET /api/agents`、`POST /api/agents`、`DELETE /api/agents/{id}` — agent 配置管理
- `GET /api/skills`、`POST /api/skills`、`DELETE /api/skills/{name}` — skill 配置管理
- `POST /api/chat/{agentId}` — 聊天（body `{"message":"..."}` → `{"answer":"..."}`）

## 测试环境

飞牛 OS（fnOS）`192.168.5.15:5666`，已有 MySQL、Redis（账号见项目根目录 `通用信息.txt`，勿提交）。
