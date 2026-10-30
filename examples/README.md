# 示例项目

不依赖 Spring 的最小可运行示例，用来验证「把框架当 jar 引进单体项目」这条路。

## 一、AgentDemo：一个 agent + 一个注解工具

```bash
export AIA_API_KEY=sk-xxx          # 必填
export AIA_BASE_URL=https://api.deepseek.com/v1   # 可选
export AIA_MODEL=deepseek-chat     # 可选

mvn -q -pl examples/minimal-agent -am compile
mvn -q -pl examples/minimal-agent dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "examples/minimal-agent/target/classes;$(cat examples/minimal-agent/cp.txt)" com.aia.example.AgentDemo
```

它做的事：注册一个 `query_order` 工具 → 让模型自己决定调用 → 把工具结果拼进回答。

## 二、RagDemo：写入知识 → 检索 → agent 自己查

同上命令，把主类换成 `com.aia.example.RagDemo`。

不配 `AIA_EMBEDDING_API_KEY` 时用内置的哈希向量（只保证链路跑通，没有语义能力）；
配上就走真实向量模型：

```bash
export AIA_EMBEDDING_API_KEY=sk-xxx
export AIA_EMBEDDING_BASE_URL=https://api.siliconflow.cn/v1   # 可选
export AIA_EMBEDDING_MODEL=Qwen/Qwen3-Embedding-0.6B          # 可选
export AIA_EMBEDDING_DIMENSION=1024                            # 可选
```

## 三、这两个示例演示了什么

| 能力 | 出现在 |
|---|---|
| 用注解定义工具并自动注册 | `AgentDemo.OrderTools` |
| 模型自主决定调用工具（tool calling 闭环） | `AgentDemo` |
| 文档切片 + 向量化 + 入库 | `RagDemo` |
| 检索 + 检索缓存 | `RagDemo` |
| 把检索包装成工具交给 agent | `RagDemo` |

更完整的接入说明见 [`docs/integration-guide.md`](../docs/integration-guide.md)。
