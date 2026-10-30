package io.github.dongcs9512.agentloom.rag;

import io.github.dongcs9512.agentloom.core.ToolInvocation;
import io.github.dongcs9512.agentloom.core.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeSearchToolTest {

    private static final EmbeddingModel EMBEDDER = new EmbeddingModel() {
        @Override
        public float[] embed(String text) {
            return new float[]{1, 0};
        }

        @Override
        public int dimension() {
            return 2;
        }
    };

    private KnowledgeSearchTool tool(KnowledgeBase kb) {
        return new KnowledgeSearchTool(kb, 3);
    }

    private KnowledgeBase kb() {
        KnowledgeBase kb = new KnowledgeBase(EMBEDDER, new InMemoryVectorStore(2), new TextChunker(), 500, 50);
        kb.addDocument("refund", "退款政策：签收后 7 天内可无理由退款。");
        kb.addDocument("shipping", "发货说明：工作日 48 小时内发出。");
        return kb;
    }

    @Test
    void exposesOpenAiToolSchema() {
        KnowledgeSearchTool t = tool(kb());
        assertEquals("search_knowledge", t.name());
        assertTrue(t.description().contains("知识库"));
        assertEquals("object", t.parameters().get("type"));
        assertEquals(List.of("query"), t.parameters().get("required"));
    }

    @Test
    void returnsMatchedChunkTextWithSourceId() {
        ToolResult result = tool(kb()).execute(
                new ToolInvocation("c1", "search_knowledge", Map.of("query", "退款")));

        assertFalse(result.isError());
        assertTrue(result.content().contains("7 天内可无理由退款"), result.content());
        assertTrue(result.content().contains("refund#0"), result.content());
    }

    @Test
    void respectsTopKLimit() {
        KnowledgeBase kb = kb();
        KnowledgeSearchTool t = new KnowledgeSearchTool(kb, 5);

        ToolResult result = t.execute(new ToolInvocation("c1", "search_knowledge", Map.of("query", "政策", "topK", 1)));

        assertFalse(result.isError());
        long numbered = result.content().lines().filter(l -> l.startsWith("[")).count();
        assertEquals(1, numbered, result.content());
    }

    @Test
    void emptyResultReturnsFriendlyMessage() {
        KnowledgeBase empty = new KnowledgeBase(EMBEDDER, new InMemoryVectorStore(2), new TextChunker(), 500, 50);

        ToolResult result = tool(empty).execute(new ToolInvocation("c1", "search_knowledge", Map.of("query", "退款")));

        assertFalse(result.isError());
        assertTrue(result.content().contains("未找到"), result.content());
    }

    @Test
    void missingQueryReturnsError() {
        ToolResult result = tool(kb()).execute(new ToolInvocation("c1", "search_knowledge", Map.of()));

        assertTrue(result.isError());
        assertTrue(result.content().contains("query"), result.content());
    }
}
