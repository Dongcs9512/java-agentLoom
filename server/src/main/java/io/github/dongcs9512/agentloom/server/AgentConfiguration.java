package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.core.ConcurrencyGuard;
import io.github.dongcs9512.agentloom.core.ConfigRepository;
import io.github.dongcs9512.agentloom.core.Guard;
import io.github.dongcs9512.agentloom.core.GuardChain;
import io.github.dongcs9512.agentloom.core.RateLimitGuard;
import io.github.dongcs9512.agentloom.core.DefaultToolResolver;
import io.github.dongcs9512.agentloom.core.InMemoryConfigRepository;
import io.github.dongcs9512.agentloom.core.ToolRegistry;
import io.github.dongcs9512.agentloom.core.ToolResolver;
import io.github.dongcs9512.agentloom.core.obs.MetricsRecorder;
import io.github.dongcs9512.agentloom.llm.openai.OpenAiCompatibleChatModel;
import io.github.dongcs9512.agentloom.llm.provider.ProviderPreset;
import io.github.dongcs9512.agentloom.llm.provider.Providers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class AgentConfiguration {

    @Value("${agentloom.llm.provider:}")
    private String provider;

    @Value("${agentloom.llm.base-url:}")
    private String baseUrl;

    @Value("${agentloom.llm.api-key:}")
    private String apiKey;

    /**
     * 实际使用的 OpenAI 兼容 baseUrl：显式配置优先，否则取供应商预设。
     */
    String resolveBaseUrl() {
        return resolveBaseUrl(provider, baseUrl);
    }

    static String resolveBaseUrl(String provider, String baseUrl) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl;
        }
        return Providers.byName(provider).map(ProviderPreset::baseUrl).orElse("");
    }

    /**
     * 默认内存实现。设 {@code aia.config.mode=jdbc} 时改由 {@link JdbcConfigConfiguration} 提供。
     */
    @Bean
    @ConditionalOnProperty(prefix = "agentloom.config", name = "mode", havingValue = "memory", matchIfMissing = true)
    public ConfigRepository configRepository() {
        return new InMemoryConfigRepository();
    }

    @Bean
    public ToolRegistry toolRegistry() {
        return new ToolRegistry();
    }

    @Bean
    public ChatModelFactory chatModelFactory(HttpClient sharedHttpClient) {
        String resolved = resolveBaseUrl();
        return modelName -> new OpenAiCompatibleChatModel(resolved, apiKey, modelName, sharedHttpClient);
    }

    /**
     * 工具作用域策略。想按租户/角色动态分配工具时，在自己的配置类里定义同名 {@link ToolResolver} Bean 覆盖它。
     */
    @Bean
    @ConditionalOnMissingBean(ToolResolver.class)
    public ToolResolver toolResolver() {
        return new DefaultToolResolver();
    }

    @Bean
    public ScenarioSettings scenarioSettings(org.springframework.core.env.Environment environment) {
        return ScenarioSettings.from(environment);
    }

    /**
     * 并发防护：限流（可选）+ 并发闸门。饱和时快速失败，避免线程与连接被打满。
     */
    @Bean
    public GuardChain agentGuardChain(
            @Value("${agentloom.concurrency.max-concurrent:32}") int maxConcurrent,
            @Value("${agentloom.concurrency.max-wait-ms:2000}") long maxWaitMs,
            @Value("${agentloom.concurrency.rate-limit-per-second:0}") int rateLimitPerSecond) {
        List<Guard> guards = new ArrayList<>();
        if (rateLimitPerSecond > 0) {
            guards.add(new RateLimitGuard(rateLimitPerSecond));
        }
        guards.add(new ConcurrencyGuard("模型", maxConcurrent, maxWaitMs));
        return new GuardChain(guards);
    }

    @Bean
    public AgentRuntime agentRuntime(ConfigRepository configRepository, ToolRegistry toolRegistry,
                                     ChatModelFactory chatModelFactory, ToolResolver toolResolver,
                                     ObjectProvider<SessionSupport> sessionSupport,
                                     ScenarioSettings scenarioSettings,
                                     ScenarioRouterFactory scenarioRouterFactory,
                                     GuardChain agentGuardChain,
                                     MetricsRecorder metricsRecorder) {
        SessionSupport base = sessionSupport.getIfAvailable(SessionSupport::disabled);
        // 会话模块没配场景路由时，按 aia.scenarios.strategy 组装
        if (!base.hasScenarioRouter() && scenarioSettings.enabled()) {
            base = base.withScenarioRouter(scenarioRouterFactory.create());
        }
        return new AgentRuntime(configRepository, toolRegistry, chatModelFactory, toolResolver, base,
                agentGuardChain, metricsRecorder);
    }
}
