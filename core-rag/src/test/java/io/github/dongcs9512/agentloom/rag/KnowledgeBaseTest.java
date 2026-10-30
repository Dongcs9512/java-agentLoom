package io.github.dongcs9512.agentloom.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    static class CountingEmbedder implements EmbeddingModel {
        int singleCalls;
        int batchCalls;

        @Override
        public int dimension() {
            return 2;
        }

        @Override
        public float[] embed(String text) {
            singleCalls++;
            return new float[]{1, 0};
        }

        @Override
        public java.util.List<float[]> embedBatch(java.util.List<String> texts) {
            batchCalls++;
            return texts.stream().map(t -> new float[]{1, 0}).toList();
        }
    }

    @Test
    void addDocumentReturnsChunkCount() {
        int count = kb().addDocument("d1", "这是一段用于测试分片的文本内容，长度足够被切分成多个块。");
        assertTrue(count > 1, "长文本应被切成多个分片，实际 " + count);
    }

    @Test
    void addDocumentEmbedsChunksInOneBatch() {
        CountingEmbedder embedder = new CountingEmbedder();
        KnowledgeBase kb = new KnowledgeBase(embedder, new InMemoryVectorStore(2), new TextChunker(), 10, 2);

        kb.addDocument("d1", "这是一段用于测试分片的文本内容，长度足够被切分成多个块。");

        assertEquals(1, embedder.batchCalls);
        assertEquals(0, embedder.singleCalls);
    }

    static class RecordingStore implements VectorStore {
        private final VectorStore delegate;
        int lastTopK;

        RecordingStore(VectorStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void upsert(List<VectorRecord> records) {
            delegate.upsert(records);
        }

        @Override
        public List<SearchHit> search(VectorQuery query) {
            lastTopK = query.topK();
            return delegate.search(query);
        }

        @Override
        public void delete(List<String> ids) {
            delegate.delete(ids);
        }

        @Override
        public void update(List<VectorRecord> records) {
            delegate.update(records);
        }

        @Override
        public Set<VectorStoreCapability> capabilities() {
            return delegate.capabilities();
        }
    }

    @Test
    void rerankReordersResultsAndTrimsToTopK() {
        RecordingStore store = new RecordingStore(new InMemoryVectorStore(2));
        // 重排模型把候选里文本含「发货」的排到最前
        RerankModel rerank = (query, documents, topN) -> {
            List<RerankResult> results = new java.util.ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                results.add(new RerankResult(i, documents.get(i).contains("发货") ? 0.99 : 0.1));
            }
            results.sort((a, b) -> Double.compare(b.score(), a.score()));
            return results.subList(0, Math.min(topN, results.size()));
        };
        KnowledgeBase kb = new KnowledgeBase(new FakeEmbedder(), store, new TextChunker(), 500, 50, rerank);
        kb.addDocument("refund", "退款政策：签收后 7 天内可无理由退款。");
        kb.addDocument("shipping", "发货说明：工作日 48 小时内发出。");

        List<SearchHit> hits = kb.search("政策", 1);

        assertEquals(1, hits.size());
        assertEquals("shipping#0", hits.get(0).id());
        assertTrue(store.lastTopK > 1, "粗召回应放大数量，实际 " + store.lastTopK);
    }

    @Test
    void withoutRerankUsesExactTopK() {
        RecordingStore store = new RecordingStore(new InMemoryVectorStore(2));
        KnowledgeBase kb = new KnowledgeBase(new FakeEmbedder(), store, new TextChunker(), 500, 50);
        kb.addDocument("refund", "退款政策：签收后 7 天内可无理由退款。");

        kb.search("政策", 3);

        assertEquals(3, store.lastTopK);
    }
}
