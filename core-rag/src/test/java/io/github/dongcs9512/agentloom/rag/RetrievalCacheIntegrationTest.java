package io.github.dongcs9512.agentloom.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalCacheIntegrationTest {

    /** 记录调用次数，用来验证缓存是否真的省掉了外部调用。 */
    static class CountingStore implements VectorStore {
        final AtomicInteger searches = new AtomicInteger();
        private final VectorStore delegate;

        CountingStore(VectorStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void upsert(List<VectorRecord> records) {
            delegate.upsert(records);
        }

        @Override
        public List<SearchHit> search(VectorQuery query) {
            searches.incrementAndGet();
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

    static class CountingEmbedder implements EmbeddingModel {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public float[] embed(String text) {
            calls.incrementAndGet();
            return new float[]{1, 0};
        }

        @Override
        public int dimension() {
            return 2;
        }
    }

    private KnowledgeBase kb(CountingStore store, CountingEmbedder embedder, RetrievalCache cache) {
        return new KnowledgeBase(embedder, store, new TextChunker(), 500, 50, null, cache);
    }

    @Test
    void secondIdenticalSearchSkipsStoreAndEmbedding() {
        CountingStore store = new CountingStore(new InMemoryVectorStore(2));
        CountingEmbedder embedder = new CountingEmbedder();
        KnowledgeBase kb = kb(store, embedder, new InMemoryRetrievalCache(10, 0));
        kb.addDocument("d1", "退款政策：签收后 7 天内可无理由退款。");

        kb.search("退款怎么弄", 3);
        int storeCalls = store.searches.get();
        int embedCalls = embedder.calls.get();

        List<SearchHit> second = kb.search("退款怎么弄？", 3);

        assertEquals(storeCalls, store.searches.get(), "命中缓存不应再查向量库");
        assertEquals(embedCalls, embedder.calls.get(), "命中缓存不应再向量化");
        assertTrue(!second.isEmpty());
    }

    @Test
    void differentTopKUsesSeparateCacheEntries() {
        CountingStore store = new CountingStore(new InMemoryVectorStore(2));
        KnowledgeBase kb = kb(store, new CountingEmbedder(), new InMemoryRetrievalCache(10, 0));
        kb.addDocument("d1", "退款政策：签收后 7 天内可无理由退款。");

        kb.search("退款", 3);
        int afterFirst = store.searches.get();
        kb.search("退款", 5);

        assertEquals(afterFirst + 1, store.searches.get(), "不同 topK 结果不同，不能共用缓存");
    }

    @Test
    void addingDocumentInvalidatesCache() {
        CountingStore store = new CountingStore(new InMemoryVectorStore(2));
        KnowledgeBase kb = kb(store, new CountingEmbedder(), new InMemoryRetrievalCache(10, 0));
        kb.addDocument("d1", "退款政策：签收后 7 天内可无理由退款。");
        kb.search("政策", 3);

        kb.addDocument("d2", "发票说明：订单完成后可申请电子发票。");
        List<SearchHit> hits = kb.search("政策", 3);

        assertTrue(hits.stream().anyMatch(h -> "d2#0".equals(h.id())), "写入后应立刻能搜到新文档");
    }

    @Test
    void deletionInvalidatesCache() {
        CountingStore store = new CountingStore(new InMemoryVectorStore(2));
        KnowledgeBase kb = kb(store, new CountingEmbedder(), new InMemoryRetrievalCache(10, 0));
        kb.addDocument("d1", "退款政策：签收后 7 天内可无理由退款。");
        kb.search("退款", 3);

        kb.deleteDocument("d1");

        assertTrue(kb.search("退款", 3).isEmpty(), "删除后不应再命中旧缓存");
    }

    @Test
    void withoutCacheEverySearchHitsStore() {
        CountingStore store = new CountingStore(new InMemoryVectorStore(2));
        KnowledgeBase kb = kb(store, new CountingEmbedder(), null);
        kb.addDocument("d1", "退款政策：签收后 7 天内可无理由退款。");

        kb.search("退款", 3);
        kb.search("退款", 3);

        assertEquals(2, store.searches.get());
    }
}
