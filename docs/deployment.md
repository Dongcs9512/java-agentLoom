# 部署指南

## 1. 环境要求

| 项 | 要求 | 说明 |
|---|---|---|
| JDK | 17+ | 编译目标就是 17 |
| 内存 | 最小 512MB | 单实例；模型调用是 IO 密集，不占太多堆 |
| MySQL | 8.0+ | 存 agent/skill/槽位配置、会话归档（可选） |
| Redis | 7+（推荐 8.x） | 会话、缓存、检索缓存；8.x 还能当向量库用 |
| Elasticsearch | 8.x | 向量库（可选，多实例时必需） |

**最小可用**：只要 JDK。MySQL/Redis/ES 全部可选，不配也能跑（用内存实现，重启丢数据）。

## 2. 三种部署方式

### 2.1 直接跑 jar（最快）

```bash
java -jar agentloom-server-0.1.0-SNAPSHOT.jar \
  --server.port=8080 \
  --agentloom.llm.provider=deepseek \
  --agentloom.llm.api-key=sk-xxx \
  --agentloom.rag.enabled=true \
  --agentloom.rag.embedding.provider=siliconflow \
  --agentloom.rag.embedding.api-key=sk-yyy
```

### 2.2 Docker

```bash
docker build -t agentloom-server:0.1.0 -f deploy/Dockerfile .
docker run -d --name agentloom-server -p 8080:8080 \
  -e AGENTLOOM_LLM_API_KEY=sk-xxx \
  -e AGENTLOOM_EMBEDDING_API_KEY=sk-yyy \
  agentloom-server:0.1.0
```

### 2.3 docker-compose（含依赖服务）

[`deploy/docker-compose.yml`](../deploy/docker-compose.yml) 里有完整的一套：
应用 + MySQL + Redis + Elasticsearch。

```bash
cd deploy
cp .env.example .env      # 填 key 和密码
docker compose up -d
docker compose logs -f app
```

> **ES 注意**：宿主机要先执行 `sudo sysctl -w vm.max_map_count=262144`，
> 否则容器启动即退，日志报 `max virtual memory areas ... too low`。

## 3. 依赖服务准备

### 3.1 MySQL

两种方式任选：

**方式一（推荐生产）**：直接用仓库根目录的 [`init.sql`](../init.sql) 一次建好库和全部表——

```bash
mysql -u root -p < init.sql
```

好处是应用账号不需要 DDL 权限，DBA 可以统一管控表结构。

**方式二**：只建库，表结构由应用启动时自动创建（含 `slots` 列的补列逻辑）：

```sql
CREATE DATABASE ai_agent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'agentloom'@'%' IDENTIFIED BY 'your-password';
GRANT ALL PRIVILEGES ON ai_agent.* TO 'agentloom'@'%';
```

建议给账号最小权限：`SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX`。

### 3.2 Redis

```conf
requirepass your-password
maxmemory-policy allkeys-lru
```

如果只当缓存用，`maxmemory-policy allkeys-lru` 就够了；
**会话数据也在 Redis 时不要设 `noeviction` 以外的激进策略**，否则会话可能被挤掉。

### 3.3 Elasticsearch

不用手动建索引——`ElasticsearchVectorStore` 首次写入时会按维度自动创建
（`dense_vector` + `cosine` + `flattened` metadata）。

## 4. 配置

### 4.1 三种来源（优先级从高到低）

1. 启动参数：`--agentloom.llm.api-key=sk-xxx`
2. 环境变量：`AGENTLOOM_LLM_API_KEY=sk-xxx`（Spring 的松散绑定，`agentloom.llm.api-key` → `AGENTLOOM_LLM_API_KEY`）
3. 配置文件：`application.yml`

**生产建议用环境变量传密钥**，不要写进镜像。

### 4.2 生产配置模板

```yaml
server:
  port: 8080
  shutdown: graceful          # 必须：滚动发布不丢在途请求

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s

agentloom:
  llm:
    provider: deepseek
    api-key: ${AGENTLOOM_LLM_API_KEY}

  concurrency:
    max-concurrent: 32         # 按单机承载能力设置，先用压测定
    max-wait-ms: 2000

  config:
    mode: jdbc                 # 配置落库
    datasource:
      url: jdbc:mysql://mysql:3306/ai_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
      username: agentloom
      password: ${AGENTLOOM_DB_PASSWORD}

  rag:
    enabled: true
    store: es                  # 多实例必须用 es，不能用 memory
    embedding:
      provider: siliconflow
      api-key: ${AGENTLOOM_EMBEDDING_API_KEY}
    es:
      url: http://es:9200
      index: agentloom-kb
    cache:
      store: composite         # L1 内存 + L2 Redis
      redis:
        host: redis
        password: ${AGENTLOOM_REDIS_PASSWORD}
    rerank:
      enabled: true

  session:
    enabled: true
    store: composite           # 会话也走两级
    redis:
      host: redis
      password: ${AGENTLOOM_REDIS_PASSWORD}
```

## 5. 反向代理（Nginx）

**流式接口必须关掉缓冲**，否则 SSE 会被攒着一次性吐出来，首字延迟极高：

```nginx
location /api/chat/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Connection '';
    proxy_buffering off;          # ← 关键
    proxy_cache off;
    chunked_transfer_encoding on;
    proxy_read_timeout 300s;      # 长回答要留够时间
}
```

完整示例见 [`deploy/nginx.conf`](../deploy/nginx.conf)。

## 6. 服务化

### Linux（systemd）

```bash
sudo cp deploy/ai-agent.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now ai-agent
sudo journalctl -u ai-agent -f
```

### Windows（NSSM）

```powershell
nssm install agentloom-server "C:\Program Files\Java\jdk-17\bin\java.exe"
nssm set agentloom-server AppParameters "-jar C:\aia\agentloom-server.jar --spring.profiles.active=prod"
nssm set agentloom-server AppEnvironmentExtra AGENTLOOM_LLM_API_KEY=sk-xxx
nssm start agentloom-server
```

## 7. 多实例与负载均衡

前提（缺一不可）：

- `agentloom.rag.store=es`：各实例共用同一 ES，否则知识不一致
- 会话/缓存 `composite` 或 `redis`：共用同一 Redis
- 网关按 `/api/health` 做健康检查，**返回非 `up` 就摘流量**

实测（两实例共用 Redis）：A 查询后，B 首次查同一问题 31ms 命中（对照 95ms）；
A 写入知识后 1.2 秒内 B 的本地缓存也失效。

## 8. 滚动发布

```bash
# 1. 从负载均衡摘掉这台
curl -s http://host:8080/api/health   # 确认其它实例健康

# 2. 发 SIGTERM（不要 kill -9）
kill -TERM <pid>                       # K8s 用 terminationGracePeriodSeconds: 40

# 3. 进程会：停止接新请求 → 在途请求跑完（最多 30s）→ 退出

# 4. 起新版本，健康检查通过后再挂回负载均衡
```

`/api/health` 在收到关闭信号后立即返回 `{"status":"draining"}`，网关据此摘流量。

## 9. 监控接入

```yaml
# prometheus.yml
scrape_configs:
  - job_name: agentloom-server
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['agentloom-server-1:8080', 'agentloom-server-2:8080']
```

建议先配这几条告警（详见 [`usage.md`](usage.md) 19.3）：

| 告警 | 条件 |
|---|---|
| 过载拒绝 | `rate(agentloom_guard_rejected_total[5m]) > 0` 持续 10 分钟 |
| 问答变慢 | `histogram_quantile(0.95, rate(agentloom_agent_chat_seconds_bucket[5m])) > 10` |
| 缓存失效 | 命中率 < 30% 持续 5 分钟 |
| 错误率 | `rate(agentloom_agent_chat_seconds_count{outcome="error"}[5m]) > 0` |

## 10. 备份

| 数据 | 位置 | 建议 |
|---|---|---|
| agent/skill 配置 | MySQL `agent_config` / `skill_config` | 随业务库一起备份 |
| 会话与答案缓存 | Redis（有 TTL） | 可不备份，最多丢历史会话 |
| 知识原文 | 你自己的源文件 | **必须备份**——向量库只是索引，重建需要原文 |
| 向量库 | ES | 可从原文重建，容量大时优先备份原文 |

> 重要：**向量库是可以重建的**。备份知识原文 + 一份导入脚本，比备份 ES 索引更划算。

## 11. 故障排查

| 现象 | 可能原因 | 排查 |
|---|---|---|
| 启动报 `url attribute is not specified` | 没开 `agentloom.config.mode=jdbc` 却配了数据源 | 改 `mode` 或删掉 datasource 配置 |
| 检索结果为空 | 知识没写入 / 维度不匹配 | `POST /api/kb/search` 试搜；看 ES mapping 的 `dims` |
| ES 容器起不来 | `vm.max_map_count` 太小 | `sysctl -w vm.max_map_count=262144` |
| 流式没有逐字返回 | Nginx 缓冲没关 | `proxy_buffering off` |
| 大量 503 | 并发闸门饱和 | 看 `agentloom_guard_rejected_total`，调 `max-concurrent` 或扩容 |
| 缓存不生效 | 多实例各用内存向量库/缓存 | 统一 ES + Redis |
| 更新知识后仍返回旧内容 | 多实例缓存未失效 | 确认 `pubsub-enabled=true` 且 Redis 互通 |
| token 消耗统计偏低 | 走的是流式接口 | 流式无 usage 字段，属已知限制 |
