package com.aia.server;

import com.aia.core.ConfigRepository;
import com.aia.core.DefaultToolResolver;
import com.aia.core.InMemoryConfigRepository;
import com.aia.core.ToolRegistry;
import com.aia.core.ToolResolver;
import com.aia.llm.openai.OpenAiCompatibleChatModel;
import com.aia.llm.provider.ProviderPreset;
import com.aia.llm.provider.Providers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

@Configuration
public class AgentConfiguration {

    @Value("${aia.llm.provider:}")
    private String provider;

    @Value("${aia.llm.base-url:}")
    private String baseUrl;

    @Value("${aia.llm.api-key:}")
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
    @ConditionalOnProperty(prefix = "aia.config", name = "mode", havingValue = "memory", matchIfMissing = true)
    public ConfigRepository configRepository() {
        return new InMemoryConfigRepository();
    }

    @Bean
    public ToolRegistry toolRegistry() {
        return new ToolRegistry();
    }

    @Bean
    public ChatModelFactory chatModelFactory() {
        String resolved = resolveBaseUrl();
        return modelName -> new OpenAiCompatibleChatModel(resolved, apiKey, modelName, HttpClient.newHttpClient());
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
    public AgentRuntime agentRuntime(ConfigRepository configRepository, ToolRegistry toolRegistry,
                                     ChatModelFactory chatModelFactory, ToolResolver toolResolver) {
        return new AgentRuntime(configRepository, toolRegistry, chatModelFactory, toolResolver);
    }
}
