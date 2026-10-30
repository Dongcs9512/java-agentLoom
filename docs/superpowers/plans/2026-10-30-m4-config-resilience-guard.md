# M4：配置存库 + 弹性（超时/熔断/降级）+ Guard 实现计划

> **For agentic workers:** 使用 superpowers:executing-plans（inline）。步骤用 `- [ ]` 跟踪。

**Goal:** 交付配置数据库存储（`ConfigRepository` SPI + JDBC 实现）、弹性策略（超时/重试/熔断/降级）、预防处理 `Guard` 链。

**Architecture:** `core` 加 `AgentConfig`/`SkillConfig` 记录、`ConfigRepository` SPI、`InMemoryConfigRepository`、`ResiliencePolicy` SPI + `AbstractResiliencePolicy`、`Guard` + `GuardChain` + `RateLimitGuard`；新建 `config-jdbc` 模块（`JdbcConfigRepository`，纯 JDBC，测试用 H2 内存库）。

**Tech Stack:** 同前 + `com.h2database:h2`（config-jdbc test scope，版本 2.2.224）。Jackson 用于列表字段 JSON 序列化。

**Spec:** `docs/superpowers/specs/2026-10-30-agentloom-design.md`（§5、§6.5、§6.6、§6.7、§11 M4）

## Global Constraints

同前。核心零 Spring；`config-jdbc` 用纯 JDBC（java.sql），不引 ORM。

## Review Focus

| # | 输入/失效情形 | 合理期望 | 测试 |
|---|---|---|---|
| 1 | 弹性动作始终失败 | 重试耗尽后走 `fallback`，不向上抛 | Task 1 |
| 2 | 连续失败达阈值 | 熔断打开，后续直接 `fallback` 且不再执行动作 | Task 1 |
| 3 | 动作超过超时时间 | 抛超时→重试→最终 `fallback` | Task 1 |
| 4 | 限流超阈值 | 拒绝（before 抛异常） | Task 2 |
| 5 | 保存空 id 的 agent 配置 | `saveAgent` 抛 `IllegalArgumentException` | Task 3 |

---

### Task 1: ResiliencePolicy + AbstractResiliencePolicy

**Files:** Create `core/src/main/java/com/aia/core/ResiliencePolicy.java`、`ResilienceCallable.java`、`ResilienceContext.java`、`AbstractResiliencePolicy.java`；Test `core/src/test/java/com/aia/core/AbstractResiliencePolicyTest.java`

**Produces:**
```java
public record ResilienceContext(String name) {}
@FunctionalInterface
public interface ResilienceCallable<T> { T call() throws Exception; }
public interface ResiliencePolicy { <T> T execute(ResilienceContext ctx, ResilienceCallable<T> action); }

public abstract class AbstractResiliencePolicy implements ResiliencePolicy {
    protected Duration timeout() { return Duration.ofSeconds(30); }
    protected int maxRetries() { return 2; }
    protected int circuitBreakerThreshold() { return 5; }
    protected Duration circuitBreakerHalfOpenAfter() { return Duration.ofSeconds(30); }
    protected abstract <T> T fallback(ResilienceContext ctx, Throwable cause);
    public <T> T execute(ResilienceContext ctx, ResilienceCallable<T> action);
}
```

`execute` 语义：熔断 OPEN 且未过半开时间→直接 `fallback`；否则带超时执行动作（`CompletableFuture` + `get(timeout)`），失败重试 `maxRetries` 次；全部失败→失败计数+1，达到阈值→OPEN；调用 `fallback`。

测试：`retriesThenSucceeds`、`fallsBackAfterExhaustion`、`circuitBreakerOpensAndSkipsAction`、`timeoutTriggersFallback`（用短超时 + sleep 动作）。

### Task 2: Guard + GuardChain + RateLimitGuard

**Files:** Create `core/src/main/java/com/aia/core/Guard.java`、`GuardContext.java`、`GuardChain.java`、`RateLimitGuard.java`；Test `core/src/test/java/com/aia/core/GuardChainTest.java`

**Produces:**
```java
public record GuardContext(String key) {}
public interface Guard {
    void before(GuardContext ctx);
    void after(GuardContext ctx, Object result, Throwable error);
}
public final class GuardChain {
    public GuardChain(List<Guard> guards);
    public <T> T execute(GuardContext ctx, java.util.function.Supplier<T> action);
}
public final class RateLimitGuard implements Guard {
    public RateLimitGuard(int maxPerSecond);   // 令牌桶，超限 before 抛 IllegalStateException
}
```

测试：`guardBeforeAndAfterAreCalled`、`rateLimitRejectsOverLimit`。

### Task 3: 配置记录 + ConfigRepository SPI + 内存实现

**Files:** Create `core/src/main/java/com/aia/core/AgentConfig.java`、`SkillConfig.java`、`ConfigRepository.java`、`InMemoryConfigRepository.java`；Test `core/src/test/java/com/aia/core/InMemoryConfigRepositoryTest.java`

**Produces:**
```java
public record AgentConfig(String id, String systemPrompt, String model,
    List<String> toolNames, List<String> skillNames, Double temperature, Integer maxTokens) {}
public record SkillConfig(String name, String description, String instructions, List<String> toolNames) {}
public interface ConfigRepository {
    List<AgentConfig> loadAgents();
    List<SkillConfig> loadSkills();
    void saveAgent(AgentConfig config);   // id 为空抛 IllegalArgumentException
    void deleteAgent(String id);
    void saveSkill(SkillConfig config);   // name 为空抛 IllegalArgumentException
    void deleteSkill(String name);
}
public final class InMemoryConfigRepository implements ConfigRepository { ... }
```

测试：`saveAndLoadAgents`、`saveAgentRejectsEmptyId`、`saveAndDeleteSkill`。

### Task 4: config-jdbc 的 JdbcConfigRepository

**Files:** Create `config-jdbc/pom.xml`（依赖 aia-core、jackson、h2 test、junit）；父 pom `<modules>` 加 `config-jdbc`；Create `config-jdbc/src/main/java/com/aia/config/JdbcConfigRepository.java`；Test `config-jdbc/src/test/java/com/aia/config/JdbcConfigRepositoryTest.java`

**Produces:**
```java
public final class JdbcConfigRepository implements ConfigRepository {
    public JdbcConfigRepository(javax.sql.DataSource dataSource);  // 表结构由构造时 CREATE TABLE IF NOT EXISTS 建
}
```

表：`agent_config(id, system_prompt, model, tool_names, skill_names, temperature, max_tokens)`、`skill_config(name, description, instructions, tool_names)`；列表字段存 JSON 字符串。

测试（H2 内存库 + `org.h2.jdbcx.JdbcDataSource`）：`saveAndLoadAgentRoundTrip`、`saveAndLoadSkillRoundTrip`、`deleteAgentRemoves`。
