package com.aia.server;

import com.aia.core.ToolRegistry;
import com.aia.core.obs.MetricsRecorder;
import com.aia.llm.provider.ProviderPreset;
import com.aia.llm.provider.Providers;
import com.aia.rag.ElasticsearchVectorStore;
import com.aia.rag.EmbeddingModel;
import com.aia.rag.InMemoryVectorStore;
import com.aia.rag.KnowledgeBase;
import com.aia.rag.CompositeRetrievalCache;
import com.aia.rag.InMemoryRetrievalCache;
import com.aia.rag.KnowledgeSearchTool;
import com.aia.rag.RetrievalCache;
import com.aia.rag.RerankModel;
import com.aia.rag.TextChunker;
import com.aia.rag.VectorStore;
import com.aia.rag.openai.OpenAiCompatibleEmbeddingModel;
import com.aia.rag.openai.OpenAiCompatibleRerankModel;
import com.aia.rag.redis.RedisInvalidationSubscriber;
import com.aia.rag.redis.RedisRetrievalCache;
import com.aia.rag.redis.RedisVectorStore;
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
@ConditionalOnProperty(prefix = "aia.rag", name = "enabled", havingValue = "true")
public class RagConfiguration {

    private RedisInvalidationSubscriber invalidationSubscriber;

    @jakarta.annotation.PreDestroy
    public void stopInvalidationSubscriber() {
        if (invalidationSubscriber != null) {
            invalidationSubscriber.close();
        }
    }

    @Value("${aia.rag.embedding.provider:}")
    private String embeddingProvider;

    @Value("${aia.rag.embedding.base-url:}")
    private String embeddingBaseUrl;

    @Value("${aia.rag.embedding.api-key:}")
    private String embeddingApiKey;

    @Value("${aia.rag.embedding.model:}")
    private String embeddingModel;

    @Value("${aia.rag.embedding.dimension:0}")
    private int dimension;

    @Value("${aia.rag.store:memory}")
    private String storeType;

    @Value("${aia.rag.es.url:}")
    private String esUrl;

    @Value("${aia.rag.es.index:aia-kb}")
    private String esIndex;

    @Value("${aia.rag.redis.host:}")
    private String redisHost;

    @Value("${aia.rag.redis.port:6379}")
    private int redisPort;

    @Value("${aia.rag.redis.password:}")
    private String redisPassword;

    @Value("${aia.rag.redis.key:aia-kb}")
    private String redisKey;

    @Value("${aia.rag.chunk-size:500}")
    private int chunkSize;

    @Value("${aia.rag.chunk-overlap:50}")
    private int chunkOverlap;

    @Value("${aia.rag.top-k:5}")
    private int topK;

    @Value("${aia.rag.rerank.provider:}")
    private String rerankProvider;

    @Value("${aia.rag.rerank.base-url:}")
    private String rerankBaseUrl;

    @Value("${aia.rag.rerank.api-key:}")
    private String rerankApiKey;

    @Value("${aia.rag.rerank.model:}")
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
    @ConditionalOnProperty(prefix = "aia.rag.rerank", name = "enabled", havingValue = "true")
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
                    throw new IllegalStateException("aia.rag.store=es 时必须配置 aia.rag.es.url");
                }
                yield new ElasticsearchVectorStore(esUrl, esIndex, dim, sharedHttpClient);
            }
            case "redis" -> {
                if (redisHost.isBlank()) {
                    throw new IllegalStateException("aia.rag.store=redis 时必须配置 aia.rag.redis.host");
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
    @ConditionalOnProperty(prefix = "aia.rag.cache", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public RetrievalCache retrievalCache(
            @Value("${aia.rag.cache.store:memory}") String cacheStore,
            @Value("${aia.rag.cache.capacity:2000}") int cacheCapacity,
            @Value("${aia.rag.cache.ttl-seconds:300}") int cacheTtlSeconds,
            @Value("${aia.rag.cache.redis.host:}") String cacheRedisHost,
            @Value("${aia.rag.cache.redis.port:6379}") int cacheRedisPort,
            @Value("${aia.rag.cache.redis.password:}") String cacheRedisPassword,
            @Value("${aia.rag.cache.redis.key-prefix:aia:retrieval:}") String cacheRedisPrefix,
            @Value("${aia.rag.cache.version-check-ms:2000}") long cacheVersionCheckMs,
            @Value("${aia.rag.cache.pubsub-enabled:true}") boolean cachePubSubEnabled) {
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
                yield new CompositeRetrievalCache(local, shared, cacheVersionCheckMs);
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
            throw new IllegalStateException("aia.rag.cache.store 选用 redis/composite 时必须配置 "
                    + "aia.rag.cache.redis.host");
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
            @Value("${aia.rag.single-flight-wait-ms:5000}") long singleFlightWaitMs) {
        // 同一问题被并发问爆时只检索一次
        KnowledgeSearchTool tool = new KnowledgeSearchTool(knowledgeBase, topK,
                new com.aia.core.SingleFlight<>(singleFlightWaitMs));
        toolRegistry.register(tool);
        return tool;
    }
}
