# M2：Skill 系统 + 四路工具定义 + 声明式 HTTP 工具 实现计划

> **For agentic workers:** 使用 superpowers:executing-plans（inline）。步骤用 `- [ ]` 跟踪。

**Goal:** 补齐 skill 系统与四种工具定义方式，新增 `tools-http` 模块（声明式 HTTP 工具 + 鉴权）。

**Architecture:** 在 `core` 加 `AbstractTool`、`Skill`/`SkillRegistry`、`@ToolDef` 注解 + `ToolScanner`；新建 `tools-http` 模块（`Authenticator` SPI + 静态/JWT/HMAC 鉴权 + `HttpTool`）；`AgentLoop` 增加带 skill 的重载。核心仍零 Spring。

**Tech Stack:** 同 M1 + `com.jayway.jsonpath:json-path`（tools-http 用，版本 2.9.0）。

**Spec:** `docs/superpowers/specs/2026-10-30-agentloom-design.md`（§5、§6.2、§6.3、§6.10、§7、§11 M2）

## Global Constraints

同 M1（Java 17、核心零 Spring、`mvn` = `E:\claudePoject\tools\apache-maven-3.9.16\bin\mvn`）。单模块测试用 `mvn test -Dtest=X -Dsurefire.failIfNoSpecifiedTests=false`。

## Review Focus

| # | 输入/失效情形 | 合理期望 | 测试 |
|---|---|---|---|
| 1 | 工具方法参数缺失（required 未传） | `ToolScanner` 生成工具 execute 时返回 error，不崩 | Task 3 |
| 2 | 反射调用方法本身抛异常 | 转成 `ToolResult.error` | Task 3 |
| 3 | HTTP 工具目标返回非 2xx | 返回 `ToolResult.error`（含状态码） | Task 6 |
| 4 | 声明式 HTTP 工具的 `bodyTemplate` 引用了不存在的参数 | 空串替换，不抛异常 | Task 6 |
| 5 | JWT/HMAC 鉴权缺密钥 | 构造时抛 `IllegalArgumentException` | Task 5 |

---

### Task 1: AbstractTool 抽象基类

**Files:** Create `core/src/main/java/com/aia/core/AbstractTool.java`；Test `core/src/test/java/com/aia/core/AbstractToolTest.java`

**Produces:**
```java
public abstract class AbstractTool implements Tool {
    public Map<String,Object> parameters() { return Map.of(); }
    public final ToolResult execute(ToolInvocation invocation) {
        try { return doExecute(invocation); }
        catch (Exception e) { return ToolResult.error(e.getMessage() == null ? "工具执行异常" : e.getMessage()); }
    }
    protected abstract ToolResult doExecute(ToolInvocation invocation);
}
```

测试：`doExecuteThrowsIsConvertedToError`（doExecute 抛异常 → execute 返回 error，content 为异常 message）；`doExecuteReturnsIsPassedThrough`（返回 ok 原样透传）。先写测试看它红，再实现。

### Task 2: Skill + SkillRegistry

**Files:** Create `core/src/main/java/com/aia/core/Skill.java`、`SkillRegistry.java`；Test `core/src/test/java/com/aia/core/SkillRegistryTest.java`

**Produces:**
```java
public interface Skill {
    String name(); String description(); String instructions();
    List<Tool> tools(); Map<String,Object> resources();
}
public final class SkillRegistry {
    public void register(Skill skill);   // 重复 name 抛 IllegalArgumentException
    public Optional<Skill> get(String name);
    public List<Skill> all();
}
```

测试：`registerAndGet`、`duplicateNameThrows`、`allReturnsInsertionOrder`。

### Task 3: @ToolDef 注解 + ToolScanner

**Files:** Create `core/src/main/java/com/aia/core/ToolDef.java`、`ToolParam.java`、`ToolScanner.java`；Test `core/src/test/java/com/aia/core/ToolScannerTest.java`

**Ruling（先记）：** 注解命名为 `@ToolDef` 而非设计文档里的 `@Tool`，因为 `com.aia.core.Tool` 已是接口，同包不能重名；且改成**方法级**注解（一个方法 = 一个工具），比类级更简单无歧义。

**Produces:**
```java
@Retention(RUNTIME) @Target(METHOD)
public @interface ToolDef { String name(); String description(); }

@Retention(RUNTIME) @Target(PARAMETER)
public @interface ToolParam { String description() default ""; boolean required() default true; }

public final class ToolScanner {
    public List<Tool> scan(Object target);   // 扫描所有 @ToolDef 方法
}
```

`scan` 对每个 `@ToolDef` 方法生成一个 `Tool`：`name`/`description` 取注解值；`parameters` 由方法参数生成 JSON Schema（类型映射 String→string、int/Integer/long→integer、double/Double/float→number、boolean/Boolean→boolean、其它→string；`@ToolParam.description` 作字段 description；required 收集进 `required` 数组）。`execute` 按参数顺序从 `ToolInvocation.arguments()` 取值、反射调用、`ToolResult.ok(String.valueOf(返回值))`（null→""）。

测试：`scansNameDescriptionAndSchema`（含 required 数组与类型映射）、`executeInvokesMethod`（参数按序传入、返回 ok）、`executeConvertsExceptionToError`、`missingRequiredParamReturnsError`。

### Task 4: tools-http 模块 + Authenticator SPI + StaticHeader

**Files:** Create `tools-http/pom.xml`（依赖 aia-core、jackson、json-path、junit）；父 pom `<modules>` 加 `tools-http`；Create `tools-http/src/main/java/com/aia/http/Authenticator.java`、`StaticHeaderAuthenticator.java`；Test `tools-http/src/test/java/com/aia/http/StaticHeaderAuthenticatorTest.java`

**Produces:**
```java
public interface Authenticator {
    Map<String,String> applyHeaders(Map<String,Object> context);
}
public final class StaticHeaderAuthenticator implements Authenticator {
    public StaticHeaderAuthenticator(Map<String,String> headers);  // 空 map 抛 IllegalArgumentException
}
```

测试：`returnsConfiguredHeaders`。

### Task 5: JwtAuthenticator + HmacSignatureAuthenticator

**Files:** Create `tools-http/src/main/java/com/aia/http/JwtAuthenticator.java`、`HmacSignatureAuthenticator.java`；Test `tools-http/src/test/java/com/aia/http/JwtAuthenticatorTest.java`、`HmacSignatureAuthenticatorTest.java`

**Produces:**
```java
public final class JwtAuthenticator implements Authenticator {
    // algorithm: "HS256" | "RS256"；secret 为空抛 IllegalArgumentException
    public JwtAuthenticator(String algorithm, String secret, Map<String,Object> claims, int expInSeconds);
    // applyHeaders 返回 {"Authorization": "Bearer <jwt>"}
}
public final class HmacSignatureAuthenticator implements Authenticator {
    public HmacSignatureAuthenticator(String accessKey, String secret);
    // 从 context 读 method/url/body；返回 X-Timestamp/X-Access-Key/X-Signature（HMAC-SHA256，Base64）
}
```

JWT 用 JDK 自带 `Mac`（HS256）/`Signature`（RS256）+ `Base64.getUrlEncoder().withoutPadding()` 手写，不引第三方。HMAC 签名字符串 = `method + "\n" + path + "\n" + timestamp + "\n" + body`。

测试：`jwtHasBearerHeaderWith3Segments`（header/payload/signature 三段、可解出 alg/claims）、`jwtRejectsEmptySecret`、`hmacProducesTimestampAccessKeySignature`（用同一 secret 复算签名一致）、`hmacRejectsEmptySecret`。

### Task 6: HttpTool（声明式 HTTP 工具）

**Files:** Create `tools-http/src/main/java/com/aia/http/HttpToolConfig.java`、`HttpTool.java`；Test `tools-http/src/test/java/com/aia/http/HttpToolTest.java`（JDK `HttpServer` 作 mock）

**Produces:**
```java
public record HttpToolConfig(String name, String description, Map<String,Object> parameters,
    String method, String url, Map<String,String> headers, String bodyTemplate,
    String extract, int timeoutMs, Authenticator authenticator) {}

public final class HttpTool implements Tool {
    public HttpTool(HttpToolConfig config);
    // execute：填 {{param}} 模板 → 拼 context{method,url,body} → 加 auth 头 + headers →
    // 发 HTTP → 2xx 按 extract(JsonPath) 裁剪 → ToolResult.ok；非 2xx → ToolResult.error(含状态码)
}
```

测试：`sendsRequestAndExtractsField`（模板填参、extract `$.data` 抠字段）、`non2xxReturnsError`、`missingTemplateParamReplacedWithEmpty`。

### Task 7: AgentLoop 支持 skill

**Files:** Modify `core/src/main/java/com/aia/core/AgentLoop.java`；Test `core/src/test/java/com/aia/core/AgentLoopSkillTest.java`

**Produces:**
```java
// 新增重载（原 3/4 参构造等价于 skills=空列表）
public AgentLoop(ChatModel model, ToolRegistry tools, List<Skill> skills, String systemPrompt, int maxIterations)
public AgentLoop(ChatModel model, ToolRegistry tools, List<Skill> skills, String systemPrompt)
```

行为：`systemPrompt` 追加技能块（`## Skill: <name>` + description + instructions）；工具解析先查 `ToolRegistry`，再查各 skill 的 `tools()`；skill 工具按 name 去重（先注册者优先）。

测试：`skillToolIsInvoked`（skill 的工具被模型调用并返回结果）、`systemPromptIncludesSkillInstructions`（传给模型的 system 消息包含 skill 名与 instructions）。原 M1 测试保持绿。
