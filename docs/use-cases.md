# 使用案例

每个案例都说明：**为什么适合这套框架**、**要配哪些东西**、**有哪些坑**。
配置细节见 [`integration-guide.md`](integration-guide.md)。

---

## 案例一：电商客服（工具调用为主）

**场景**：用户问「我的订单到哪了」「怎么退货」，需要查订单系统、查物流。

**为什么适合**：这类问题的答案不在文档里，在业务系统里。工具调用比 RAG 更直接。

**要配的**：

```java
// 1. 订单/物流查询工具（推荐声明式 HTTP，零代码）
registry.register(new HttpTool(new HttpToolConfig(
        "query_order", "根据订单号查询订单状态和物流",
        Map.of("orderId", Map.of("type", "string")),
        "POST", "http://order-service/internal/queryOrder",
        Map.of("Authorization", "Bearer {{token}}"),
        "{\"orderId\":\"{{orderId}}\"}", "$.data", 3000, null)));

// 2. 退款政策这类固定问答，用 RAG
kb.addDocument("refund", "退款政策：签收后 7 天内可无理由退款……");
registry.register(new KnowledgeSearchTool(kb, 3));

// 3. 寒暄直接走固定话术，省一次模型调用
new RuleBasedRetrievalRouter("您好，我是 XX 客服，请问有什么可以帮您？", List.of("谢谢", "再见"));
```

**坑**：

- 订单类工具**必须设超时**（`HttpToolConfig` 里的 3000ms），否则下游慢会拖死整个对话。
- 工具描述要写清楚「什么时候用」，模型才会在正确的时机调用。别写「查询订单」，写「用户询问订单状态、物流进度时必须调用」。
- 高频问题（退换货政策）开**答案缓存**，命中率会很高。

---

## 案例二：法律咨询（已完整实现并评测）

**场景**：用户说「公司把我辞退了」，AI 接待先收集情况，再交给对应专业律师分析。

**为什么适合**：这是典型的**多步流程**——先收集信息，再分派，最后基于法条回答。
需要三样东西同时具备：槽位收集、场景分派、RAG。

**要配的**：

```yaml
agentloom:
  demo:
    enabled: true      # 会生成 legal-intake + legal-labor/contract/welfare + 劳动法知识库
```

跑起来后：

```bash
# 第 1 轮：开始收集
curl -X POST localhost:8080/api/chat/legal-intake -H 'Content-Type: application/json' \
  -d '{"sessionId":"u1","message":"公司把我辞退了"}'

# 第 2~4 轮：补齐时间/地点/经过，框架会自动分派给对应律师
curl -X POST localhost:8080/api/chat/legal-intake -H 'Content-Type: application/json' \
  -d '{"sessionId":"u1","message":"上周五口头让我走的，在杭州，干了两年半"}'
```

**实测效果**：第 4 轮回答里的「7 天内」「3 个工作日」「2N」全部来自知识库原文，
并且分派到了正确的专业方向（劳动争议 → `legal-labor`）。

**坑**：

- **必填槽位不要太多**。三个左右合适，超过五个用户会烦。
- **可选槽位不能阻止下结论**——这是实测踩过的坑：只剩可选信息时模型会一直追问，永远不给结论。
  框架已修，但你自己加的槽位也要注意 `required=false`。
- **语义答案缓存绝对不能开**：实测会把 FAQ 答案套到别人的案情上（见 usage.md 16 节）。

---

## 案例三：企业内部知识问答

**场景**：员工问「报销流程是什么」「这个季度的销售政策」，答案在 Confluence/飞书文档里。

**为什么适合**：纯 RAG 场景，不需要工具，但要处理权限（不同部门看不同文档）。

**要配的**：

```java
// 文档分部门写，用 metadata 标记
kb.addDocument("finance-001", "报销流程……");   // metadata 里带 dept=finance

// 检索时按部门过滤（ES 支持 METADATA_FILTER）
new VectorQuery(vector, 5, Map.of("dept", user.getDept()));
```

**坑**：

- **权限必须在检索层做**，不能靠提示词让模型「别说」。用 `metadata` 过滤是最简单的做法。
- 文档要定期重灌。建议跑批而不是实时，避免频繁清缓存。
- 分片大小 300~500 字比较合适，太大检索精度下降，太小上下文不完整。

---

## 案例四：工单自动分诊

**场景**：用户提交工单，系统自动判断属于哪个团队（技术/账务/售后），并生成摘要。

**为什么适合**：这正是框架里「场景分派」的能力，而且是**确定性路由 + 可评测**的。

**要配的**：

```yaml
agentloom:
  scenarios:
    strategy: cascade        # 关键词 → 向量 → LLM 逐级兜底
    min-score: 3
    rules:
      - agent: ticket-tech
        keywords: 报错,闪退,登录不了,接口,500
      - agent: ticket-billing
        keywords: 发票,退款,扣费,账单,充值
```

**实测数据**（32 道标注题）：

| 策略 | 准确率 | 平均延迟 |
|---|---|---|
| cascade | 96.9% | 281ms |
| llm | 96.9% | 771ms |
| embedding | 81.2% | 414ms |
| keyword | 62.5% | 0ms |

**坑**：

- **纯关键词只有 62%**：用户说法千变万化，规则永远追不上。至少要上级联。
- **关键词要加权**：`社保*3` 才能压过 `辞退` 这种泛化词，否则交叉场景会判错。
- 上线前用自己的历史工单做一批评测集，跑 `/api/eval/routing` 对比。

---

## 案例五：产品文档助手（面向开发者）

**场景**：开发者问「这个接口怎么鉴权」「参数 limit 上限多少」，答案在 API 文档里。

**为什么适合**：文档结构清晰、问题重复率高，缓存收益大。

**要配的**：

```yaml
agentloom:
  rag:
    cache:
      store: composite      # L1 内存 + L2 Redis，热门问题跨实例命中
      ttl-seconds: 600
  answer-cache:
    enabled: true           # 高重复率场景，答案缓存收益明显
```

**坑**：

- 文档更新频繁时**缓存 TTL 要短**（5~10 分钟），或者发布后主动清缓存。
- 代码示例要单独成片，别和说明文字混在一起——模型更擅长直接引用完整代码块。
- 这类场景**答案缓存收益最大**：实测同一问题第二次直接 2ms 返回。

---

## 怎么选：一张决策表

| 你的问题主要是 | 主要用什么 | 需要 RAG | 需要多轮 |
|---|---|---|---|
| 答案在业务系统里 | 工具（HTTP/Java） | 否 | 建议 |
| 答案在文档里 | RAG | 是 | 建议 |
| 要先问清楚再回答 | 槽位收集 | 视情况 | **必须** |
| 要分给不同团队处理 | 场景分派 | 否 | 是 |
| 问题高度重复 | 答案缓存 | 视情况 | 否 |

**大多数真实场景是组合**：电商客服 = 工具 + RAG + 寒暄路由；
法律咨询 = 槽位 + 分派 + RAG。框架的价值就在于这些能力能拼在一起，不用各写一套。
