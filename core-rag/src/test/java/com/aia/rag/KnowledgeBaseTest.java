package com.aia.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeBaseTest {

    static class FakeEmbedder implements EmbeddingModel {
        @Override
        public int dimension() {
            return 2;
        }

        @Override
        public float[] embed(String text) {
            return new float[]{1, 0};
        }
    }

    private KnowledgeBase kb() {
        return new KnowledgeBase(new FakeEmbedder(), new InMemoryVectorStore(2), new TextChunker(), 10, 2);
    }

    @Test
    void addAndSearchReturnsChunks() {
        KnowledgeBase kb = kb();
        kb.addDocument("d1", "这是一段用于测试分片的文本内容，长度足够被切分成多个块。");
        List<SearchHit> hits = kb.search("查询", 3);
        assertFalse(hits.isEmpty());
        assertTrue(hits.size() <= 3);
        assertTrue(hits.stream().allMatch(h -> h.id().startsWith("d1#")));
    }

    @Test
    void searchEmptyQueryReturnsEmpty() {
        KnowledgeBase kb = kb();
        kb.addDocument("d1", "内容");
        assertTrue(kb.search("", 3).isEmpty());
    }

    @Test
    void updateChunkChangesContent() {
        KnowledgeBase kb = kb();
        kb.addDocument("d1", "这是一段用于测试分片的文本内容，长度足够被切分成多个块。");
        kb.updateChunk("d1#0", "新内容");
        List<SearchHit> hits = kb.search("查询", 100);
        assertTrue(hits.stream().anyMatch(h -> "d1#0".equals(h.id()) && "新内容".equals(h.text())));
    }

    @Test
    void updateMissingChunkIsNoop() {
        KnowledgeBase kb = kb();
        kb.addDocument("d1", "内容");
        kb.updateChunk("nonexistent", "x");
        assertTrue(kb.search("查询", 100).stream().noneMatch(h -> "nonexistent".equals(h.id())));
    }

    @Test
    void deleteDocumentRemovesAllChunks() {
        KnowledgeBase kb = kb();
        kb.addDocument("d1", "这是一段用于测试分片的文本内容，长度足够被切分成多个块。");
        kb.deleteDocument("d1");
        assertTrue(kb.search("查询", 100).isEmpty());
    }
}
