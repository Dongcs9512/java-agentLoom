package com.aia.server;

import com.aia.core.ConfigRepository;
import com.aia.core.InMemoryConfigRepository;
import com.aia.core.ToolRegistry;
import com.aia.llm.openai.OpenAiCompatibleChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

@Configuration
public class AgentConfiguration {

    @Value("${aia.llm.base-url:}")
    private String baseUrl;

    @Value("${aia.llm.api-key:}")
    private String apiKey;

    @Bean
    public ConfigRepository configRepository() {
        return new InMemoryConfigRepository();
    }

    @Bean
    public ToolRegistry toolRegistry() {
        return new ToolRegistry();
    }

    @Bean
    public ChatModelFactory chatModelFactory() {
        return modelName -> new OpenAiCompatibleChatModel(baseUrl, apiKey, modelName, HttpClient.newHttpClient());
    }

    @Bean
    public AgentRuntime agentRuntime(ConfigRepository configRepository, ToolRegistry toolRegistry,
                                     ChatModelFactory chatModelFactory) {
        return new AgentRuntime(configRepository, toolRegistry, chatModelFactory);
    }
}
