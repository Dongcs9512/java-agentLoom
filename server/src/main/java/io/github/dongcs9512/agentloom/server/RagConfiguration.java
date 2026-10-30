package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.core.ToolRegistry;
import io.github.dongcs9512.agentloom.core.obs.MetricsRecorder;
import io.github.dongcs9512.agentloom.llm.provider.ProviderPreset;
import io.github.dongcs9512.agentloom.llm.provider.Providers;
import io.github.dongcs9512.agentloom.rag.ElasticsearchVectorStore;
import io.github.dongcs9512.agentloom.rag.EmbeddingModel;
import io.github.dongcs9512.agentloom.rag.InMemoryVectorStore;
import io.github.dongcs9512.agentloom.rag.KnowledgeBase;
import io.github.dongcs9512.agentloom.rag.CompositeRetrievalCache;
import io.github.dongcs9512.agentloom.rag.InMemoryRetrievalCache;
import io.github.dongcs9512.agentloom.rag.KnowledgeSearchTool;
import io.github.dongcs9512.agentloom.rag.RetrievalCache;
import io.github.dongcs9512.agentloom.rag.RerankModel;
import io.github.dongcs9512.agentloom.rag.TextChunker;
import io.github.dongcs9512.agentloom.rag.VectorStore;
import io.github.dongcs9512.agentloom.rag.openai.OpenAiCompatibleEmbeddingModel;
import io.github.dongcs9512.agentloom.rag.openai.OpenAiCompatibleRerankModel;
import io.github.dongcs9512.agentloom.rag.redis.RedisInvalidationSubscriber;
import io.github.dongcs9512.agentloom.rag.redis.RedisRetrievalCache;
import io.github.dongcs9512.agentloom.rag.redis.RedisVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

/**
 * RAG 装配：仅在 {@code aia.rag.enabled=true} 时生效，未配置时框架其余部分照常工作。
 */
@Configuration
@ConditionalOnProperty(prefix = "agentloom.rag", name = "enabled", havingValue = "true")
public class RagConfiguration {

    private RedisInvalidationSubscriber invalidationSubscriber;

    @jakarta.annotation.PreDestroy
    public void stopInvalidationSubscriber() {
        if (invalidationSubscriber != null) {
            invalidationSubscriber.close();
        }
    }

    @Value("${agentloom.rag.embedding.provider:}")
    private String embeddingProvider;

    @Value("${agentloom.rag.embedding.base-url:}")
    private String embeddingBaseUrl;

    @Value("${agentloom.rag.embedding.api-key:}")
    private String embeddingApiKey;

    @Value("${agentloom.rag.embedding.model:}")
    private String embeddingModel;

    @Value("${agentloom.rag.embedding.dimension:0}")
    private int dimension;

    @Value("${agentloom.rag.store:memory}")
    private String storeType;

    @Value("${agentloom.rag.es.url:}")
    private String esUrl;

    @Value("${agentloom.rag.es.index:aia-kb}")
    private String esIndex;

    @Value("${agentloom.rag.redis.host:}")
    private String redisHost;

    @Value("${agentloom.rag.redis.port:6379}")
    private int redisPort;

    @Value("${agentloom.rag.redis.password:}")
    private String redisPassword;

    @Value("${agentloom.rag.redis.key:aia-kb}")
    private String redisKey;

    @Value("${agentloom.rag.chunk-size:500}")
    private int chunkSize;

    @Value("${agentloom.rag.chunk-overlap:50}")
    private int chunkOverlap;

    @Value("${agentloom.rag.top-k:5}")
    private int topK;

    @Value("${agentloom.rag.rerank.provider:}")
    private String rerankProvider;

    @Value("${agentloom.rag.rerank.base-url:}")
    private String rerankBaseUrl;

    @Value("${agentloom.rag.rerank.api-key:}")
    private String rerankApiKey;

    @Value("${agentloom.rag.rerank.model:}")
    private String rerankModelName;

    /**
     * 向量化模型。优先用显式配置的 base-url / model / dimension，
     * 留空的项从 {@code aia.rag.embedding.provider} 指向的供应商预设里取。
     */
    @Bean
    public EmbeddingModel embeddingModel(HttpClient sharedHttpClient) {
        String baseUrl = resolvedEmbeddingBaseUrl();
        String model = !embeddingModel.isBlank() ? embeddingModel : presetOf(embeddingProvider)
                .map(ProviderPreset::embeddingModel).orElse("");
        int dim = dimension > 0 ? dimension : presetOf(embeddingProvider)
                .map(ProviderPreset::embeddingDimension).orElse(0);

        if (baseUrl.isBlank() || model.isBlank()) {
            throw new IllegalStateException(
                    "未配置向量化服务：请设置 aia.rag.embedding.provider（可选 "
                            + String.join(", ", Providers.all().stream().map(ProviderPreset::name).toList())
                            + "），或显式填写 aia.rag.embedding.base-url 与 model");
        }
        if (dim <= 0) {
            throw new IllegalStateException("未配置向量维度：请设置 aia.rag.embedding.dimension");
        }
        return new OpenAiCompatibleEmbeddingModel(baseUrl, embeddingApiKey, model, dim, sharedHttpClient);
    }

    /**
     * 重排模型。默认沿用向量化的供应商与 key，只覆盖 rerank 自己的配置项。
     */
    @Bean
    @ConditionalOnProperty(prefix = "agentloom.rag.rerank", name = "enabled", havingValue = "true")
    public RerankModel rerankModel(HttpClient sharedHttpClient) {
        String providerName = rerankProvider.isBlank() ? embeddingProvider : rerankProvider;
        String baseUrl = !rerankBaseUrl.isBlank() ? rerankBaseUrl : resolvedEmbeddingBaseUrl();
        String apiKey = !rerankApiKey.isBlank() ? rerankApiKey : embeddingApiKey;
        String model = !rerankModelName.isBlank() ? rerankModelName
                : presetOf(providerName).map(ProviderPreset::rerankModel).orElse("");

        if (baseUrl.isBlank() || model.isBlank()) {
            throw new IllegalStateException(
                    "未配置重排服务：请设置 aia.rag.rerank.provider（如 siliconflow），"
                            + "或显式填写 aia.rag.rerank.base-url 与 model");
        }
        return new OpenAiCompatibleRerankModel(baseUrl, apiKey, model, sharedHttpClient);
    }

    private String resolvedEmbeddingBaseUrl() {
        return !embeddingBaseUrl.isBlank() ? embeddingBaseUrl
                : presetOf(embeddingProvider).map(ProviderPreset::baseUrl).orElse("");
    }

    private java.util.Optional<ProviderPreset> presetOf(String name) {
        return Providers.byName(name).or(() -> {
            if (name != null && !name.isBlank()) {
                Providers.require(name);   // 供应商名写错时直接报错，比运行时才暴露要好
            }
            return java.util.Optional.empty();
        });
    }

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel, HttpClient sharedHttpClient) {
        int dim = embeddingModel.dimension();
        return switch (storeType == null ? "memory" : storeType.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "es", "elasticsearch" -> {
                if (esUrl.isBlank()) {
                    throw new IllegalStateException("agentloom.rag.store=es 时必须配置 aia.rag.es.url");
                }
                yield new ElasticsearchVectorStore(esUrl, esIndex, dim, sharedHttpClient);
            }
            case "redis" -> {
                if (redisHost.isBlank()) {
                    throw new IllegalStateException("agentloom.rag.store=redis 时必须配置 aia.rag.redis.host");
                }
                yield new RedisVectorStore(redisHost, redisPort, redisPassword, redisKey, dim);
            }
            case "memory" -> new InMemoryVectorStore(dim);
            default -> throw new IllegalStateException(
                    "未知的向量库类型: " + storeType + "，可选 memory / es / redis");
        };
    }

    /**
     * 检索结果缓存：缓存「这个问题命中哪几篇片段」而不是回答，粒度安全。
     */
    @Bean
    @ConditionalOnProperty(prefix = "agentloom.rag.cache", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public RetrievalCache retrievalCache(
            @Value("${agentloom.rag.cache.store:memory}") String cacheStore,
            @Value("${agentloom.rag.cache.capacity:2000}") int cacheCapacity,
            @Value("${agentloom.rag.cache.ttl-seconds:300}") int cacheTtlSeconds,
            @Value("${agentloom.rag.cache.redis.host:}") String cacheRedisHost,
            @Value("${agentloom.rag.cache.redis.port:6379}") int cacheRedisPort,
            @Value("${agentloom.rag.cache.redis.password:}") String cacheRedisPassword,
            @Value("${agentloom.rag.cache.redis.key-prefix:aia:retrieval:}") String cacheRedisPrefix,
            @Value("${agentloom.rag.cache.version-check-ms:2000}") long cacheVersionCheckMs,
            @Value("${agentloom.rag.cache.pubsub-enabled:true}") boolean cachePubSubEnabled,
            MetricsRecorder metricsRecorder) {
        InMemoryRetrievalCache local = new InMemoryRetrievalCache(cacheCapacity, cacheTtlSeconds);
        String type = cacheStore == null ? "memory" : cacheStore.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (type) {
            case "redis" -> new RedisRetrievalCache(requireCacheRedis(cacheRedisHost), cacheRedisPort,
                    cacheRedisPassword, cacheRedisPrefix, cacheTtlSeconds);
            case "composite" -> {
                RedisRetrievalCache shared = new RedisRetrievalCache(requireCacheRedis(cacheRedisHost),
                        cacheRedisPort, cacheRedisPassword, cacheRedisPrefix, cacheTtlSeconds);
                startInvalidationSubscriber(cachePubSubEnabled, cacheRedisHost, cacheRedisPort,
                        cacheRedisPassword, shared.invalidationChannel(), local);
                yield new CompositeRetrievalCache(local, shared, cacheVersionCheckMs, metricsRecorder);
            }
            default -> local;
        };
    }

    /**
     * 订阅失效广播：别的实例写入知识库时立刻清掉本地缓存，把陈旧窗口从秒级压到毫秒级。
     */
    private void startInvalidationSubscriber(boolean enabled, String host, int port, String password,
                                             String channel, InMemoryRetrievalCache local) {
        if (!enabled) {
            return;
        }
        invalidationSubscriber = new RedisInvalidationSubscriber(host, port, password, channel, local::clear);
        invalidationSubscriber.start();
    }

    private static String requireCacheRedis(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("agentloom.rag.cache.store 选用 redis/composite 时必须配置 "
                    + "agentloom.rag.cache.redis.host");
        }
        return host;
    }

    @Bean
    public KnowledgeBase knowledgeBase(EmbeddingModel embeddingModel, VectorStore vectorStore,
                                       ObjectProvider<RerankModel> rerankModelProvider,
                                       ObjectProvider<RetrievalCache> retrievalCache,
                                       MetricsRecorder metricsRecorder) {
        return new KnowledgeBase(embeddingModel, vectorStore, new TextChunker(), chunkSize, chunkOverlap,
                rerankModelProvider.getIfAvailable(), retrievalCache.getIfAvailable(), metricsRecorder);
    }

    /**
     * 把知识库检索注册成全局工具，所有 agent 都可调用 search_knowledge。
     */
    @Bean
    public KnowledgeSearchTool knowledgeSearchTool(KnowledgeBase knowledgeBase, ToolRegistry toolRegistry,
            @Value("${agentloom.rag.single-flight-wait-ms:5000}") long singleFlightWaitMs) {
        // 同一问题被并发问爆时只检索一次
        KnowledgeSearchTool tool = new KnowledgeSearchTool(knowledgeBase, topK,
                new io.github.dongcs9512.agentloom.core.SingleFlight<>(singleFlightWaitMs));
        toolRegistry.register(tool);
        return tool;
    }
}
