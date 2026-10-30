package com.aia.server;

import com.aia.core.ToolRegistry;
import com.aia.rag.ElasticsearchVectorStore;
import com.aia.rag.EmbeddingModel;
import com.aia.rag.InMemoryVectorStore;
import com.aia.rag.KnowledgeBase;
import com.aia.rag.KnowledgeSearchTool;
import com.aia.rag.TextChunker;
import com.aia.rag.VectorStore;
import com.aia.rag.openai.OpenAiCompatibleEmbeddingModel;
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

    @Value("${aia.rag.embedding.base-url:}")
    private String embeddingBaseUrl;

    @Value("${aia.rag.embedding.api-key:}")
    private String embeddingApiKey;

    @Value("${aia.rag.embedding.model:text-embedding-v3}")
    private String embeddingModel;

    @Value("${aia.rag.embedding.dimension:1024}")
    private int dimension;

    @Value("${aia.rag.es.url:}")
    private String esUrl;

    @Value("${aia.rag.es.index:aia-kb}")
    private String esIndex;

    @Value("${aia.rag.chunk-size:500}")
    private int chunkSize;

    @Value("${aia.rag.chunk-overlap:50}")
    private int chunkOverlap;

    @Value("${aia.rag.top-k:5}")
    private int topK;

    @Bean
    public EmbeddingModel embeddingModel() {
        return new OpenAiCompatibleEmbeddingModel(embeddingBaseUrl, embeddingApiKey, embeddingModel, dimension,
                HttpClient.newHttpClient());
    }

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        int dim = embeddingModel.dimension();
        if (esUrl == null || esUrl.isBlank()) {
            return new InMemoryVectorStore(dim);
        }
        return new ElasticsearchVectorStore(esUrl, esIndex, dim, HttpClient.newHttpClient());
    }

    @Bean
    public KnowledgeBase knowledgeBase(EmbeddingModel embeddingModel, VectorStore vectorStore) {
        return new KnowledgeBase(embeddingModel, vectorStore, new TextChunker(), chunkSize, chunkOverlap);
    }

    /**
     * 把知识库检索注册成全局工具，所有 agent 都可调用 search_knowledge。
     */
    @Bean
    public KnowledgeSearchTool knowledgeSearchTool(KnowledgeBase knowledgeBase, ToolRegistry toolRegistry) {
        KnowledgeSearchTool tool = new KnowledgeSearchTool(knowledgeBase, topK);
        toolRegistry.register(tool);
        return tool;
    }
}
