package com.aia.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class KnowledgeBase {

    /** 配了重排模型时，先按这个倍数粗召回，再交给重排收敛。 */
    private static final int CANDIDATE_MULTIPLIER = 4;

    private final EmbeddingModel embedder;
    private final VectorStore store;
    private final TextChunker chunker;
    private final int chunkSize;
    private final int overlap;
    private final RerankModel rerank;
    private final Map<String, List<String>> docChunks = new LinkedHashMap<>();

    public KnowledgeBase(EmbeddingModel embedder, VectorStore store, TextChunker chunker, int chunkSize, int overlap) {
        this(embedder, store, chunker, chunkSize, overlap, null);
    }

    public KnowledgeBase(EmbeddingModel embedder, VectorStore store, TextChunker chunker, int chunkSize, int overlap,
                         RerankModel rerank) {
        this.embedder = embedder;
        this.store = store;
        this.chunker = chunker;
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.rerank = rerank;
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
        return chunks.size();
    }

    public List<SearchHit> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        float[] vector = embedder.embed(query);
        if (rerank == null) {
            return store.search(new VectorQuery(vector, topK, Map.of()));
        }
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
    }

    public void deleteDocument(String docId) {
        List<String> ids = docChunks.remove(docId);
        if (ids != null && !ids.isEmpty()) {
            store.delete(ids);
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
