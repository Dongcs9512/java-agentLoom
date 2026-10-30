package io.github.dongcs9512.agentloom.rag;

import io.github.dongcs9512.agentloom.core.obs.MetricsRecorder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class KnowledgeBase {

    /** 配了重排模型时，先按这个倍数粗召回，再交给重排收敛。 */
    private static final int CANDIDATE_MULTIPLIER = 4;

    private final EmbeddingModel embedder;
    private final VectorStore store;
    private final TextChunker chunker;
    private final int chunkSize;
    private final int overlap;
    private final RerankModel rerank;
    private final RetrievalCache cache;
    private final MetricsRecorder metrics;
    private final Map<String, List<String>> docChunks = new LinkedHashMap<>();

    public KnowledgeBase(EmbeddingModel embedder, VectorStore store, TextChunker chunker, int chunkSize, int overlap) {
        this(embedder, store, chunker, chunkSize, overlap, null, null);
    }

    public KnowledgeBase(EmbeddingModel embedder, VectorStore store, TextChunker chunker, int chunkSize, int overlap,
                         RerankModel rerank) {
        this(embedder, store, chunker, chunkSize, overlap, rerank, null);
    }

    /**
     * @param cache 检索结果缓存，可为 null（关闭缓存）
     */
    public KnowledgeBase(EmbeddingModel embedder, VectorStore store, TextChunker chunker, int chunkSize, int overlap,
                         RerankModel rerank, RetrievalCache cache) {
        this(embedder, store, chunker, chunkSize, overlap, rerank, cache, MetricsRecorder.noop());
    }

    public KnowledgeBase(EmbeddingModel embedder, VectorStore store, TextChunker chunker, int chunkSize, int overlap,
                         RerankModel rerank, RetrievalCache cache, MetricsRecorder metrics) {
        this.embedder = embedder;
        this.store = store;
        this.chunker = chunker;
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.rerank = rerank;
        this.cache = cache;
        this.metrics = metrics == null ? MetricsRecorder.noop() : metrics;
    }

    /**
     * 切分、向量化并写入一个文档，返回切分出的分片数。
     */
    public int addDocument(String docId, String text) {
        List<String> chunks = chunker.chunk(text, chunkSize, overlap);
        List<float[]> vectors = embedder.embedBatch(chunks);
        List<VectorRecord> records = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunkId = docId + "#" + i;
            records.add(new VectorRecord(chunkId, chunks.get(i), vectors.get(i), Map.of("docId", docId)));
            ids.add(chunkId);
        }
        store.upsert(records);
        docChunks.put(docId, ids);
        invalidateCache();
        return chunks.size();
    }

    public List<SearchHit> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String scope = "topK=" + topK + (rerank == null ? "" : "+rerank");
        if (cache != null) {
            Optional<List<SearchHit>> cached = cache.get(scope, query);
            if (cached.isPresent()) {
                metrics.count("agentloom.cache.retrieval", Map.of("result", "hit"));
                return cached.get();
            }
            metrics.count("agentloom.cache.retrieval", Map.of("result", "miss"));
        }

        long start = System.nanoTime();
        float[] vector = embedder.embed(query);
        List<SearchHit> hits = rerank == null
                ? store.search(new VectorQuery(vector, topK, Map.of()))
                : reranked(query, vector, topK);

        if (cache != null) {
            cache.put(scope, query, hits);
        }
        metrics.time("agentloom.rag.search", Math.round((System.nanoTime() - start) / 1_000_000.0),
                Map.of("rerank", String.valueOf(rerank != null)));
        return hits;
    }

    private List<SearchHit> reranked(String query, float[] vector, int topK) {
        List<SearchHit> candidates = store.search(new VectorQuery(vector, topK * CANDIDATE_MULTIPLIER, Map.of()));
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<String> texts = candidates.stream().map(SearchHit::text).toList();
        List<RerankResult> ranked = rerank.rerank(query, texts, topK);

        List<SearchHit> hits = new ArrayList<>();
        for (RerankResult result : ranked) {
            SearchHit hit = candidates.get(result.index());
            hits.add(new SearchHit(hit.id(), hit.text(), (float) result.score(), hit.metadata()));
        }
        return hits;
    }

    public void updateChunk(String chunkId, String newText) {
        if (!containsChunk(chunkId)) {
            return;
        }
        store.update(List.of(new VectorRecord(chunkId, newText, embedder.embed(newText), Map.of())));
        invalidateCache();
    }

    public void deleteDocument(String docId) {
        List<String> ids = docChunks.remove(docId);
        if (ids != null && !ids.isEmpty()) {
            store.delete(ids);
        }
        invalidateCache();
    }

    private void invalidateCache() {
        if (cache != null) {
            cache.clear();
        }
    }

    private boolean containsChunk(String chunkId) {
        for (List<String> ids : docChunks.values()) {
            if (ids.contains(chunkId)) {
                return true;
            }
        }
        return false;
    }
}
