package com.aia.example;

import com.aia.core.AgentLoop;
import com.aia.core.ToolRegistry;
import com.aia.llm.ChatModel;
import com.aia.llm.openai.OpenAiCompatibleChatModel;
import com.aia.rag.EmbeddingModel;
import com.aia.rag.InMemoryRetrievalCache;
import com.aia.rag.InMemoryVectorStore;
import com.aia.rag.KnowledgeBase;
import com.aia.rag.KnowledgeSearchTool;
import com.aia.rag.SearchHit;
import com.aia.rag.TextChunker;
import com.aia.rag.openai.OpenAiCompatibleEmbeddingModel;

import java.net.http.HttpClient;
import java.util.List;

/**
 * RAG 示例：写入知识 → 检索 → 让 agent 自己检索后回答。
 *
 * <p>有 {@code AIA_EMBEDDING_API_KEY} 时用真实向量模型，否则退回到示例用的哈希向量（仅能跑通链路）。
 */
public final class RagDemo {

    public static void main(String[] args) {
        EmbeddingModel embedder = buildEmbedder();
        boolean demoEmbedder = embedder instanceof DemoEmbeddingModel;
        if (demoEmbedder) {
            System.out.println("（未配置 AIA_EMBEDDING_API_KEY，使用示例哈希向量，检索结果只反映字符重叠）");
        }

        InMemoryVectorStore store = new InMemoryVectorStore(embedder.dimension());
        KnowledgeBase kb = new KnowledgeBase(embedder, store, new TextChunker(), 200, 20,
                null, new InMemoryRetrievalCache(500, 300));

        kb.addDocument("refund", "退款政策：签收后 7 天内可无理由退款，退款将在 3 个工作日内原路返回。");
        kb.addDocument("shipping", "发货说明：工作日 16 点前下单当天发出，偏远地区顺延一天。");
        kb.addDocument("invoice", "发票说明：订单完成后可在订单详情页申请电子发票，3 个工作日内开具。");

        System.out.println("\n== 直接检索 ==");
        List<SearchHit> hits = kb.search("退款要多久", 2);
        hits.forEach(hit -> System.out.printf("  %s  score=%.3f  %s%n", hit.id(), hit.score(), hit.text()));

        String apiKey = System.getenv("AIA_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("\n（未配置 AIA_API_KEY，跳过 agent 回答部分）");
            return;
        }

        ChatModel model = new OpenAiCompatibleChatModel(
                env("AIA_BASE_URL", "https://api.deepseek.com/v1"), apiKey,
                env("AIA_MODEL", "deepseek-chat"), HttpClient.newHttpClient());

        ToolRegistry tools = new ToolRegistry();
        tools.register(new KnowledgeSearchTool(kb, 3));

        AgentLoop loop = new AgentLoop(model, tools,
                "你是电商客服。回答政策类问题前必须先调用 search_knowledge 检索知识库，只依据检索结果回答。");
        System.out.println("\n== agent 回答 ==");
        System.out.println(loop.run("退款要多久能到账？"));
    }

    private static EmbeddingModel buildEmbedder() {
        String key = System.getenv("AIA_EMBEDDING_API_KEY");
        if (key == null || key.isBlank()) {
            return new DemoEmbeddingModel(64);
        }
        return new OpenAiCompatibleEmbeddingModel(
                env("AIA_EMBEDDING_BASE_URL", "https://api.siliconflow.cn/v1"),
                key,
                env("AIA_EMBEDDING_MODEL", "Qwen/Qwen3-Embedding-0.6B"),
                Integer.parseInt(env("AIA_EMBEDDING_DIMENSION", "1024")),
                HttpClient.newHttpClient());
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
